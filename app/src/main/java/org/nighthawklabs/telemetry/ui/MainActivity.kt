package org.nighthawklabs.telemetry.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.nighthawklabs.telemetry.ObdTelemetryApplication
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.data.repository.VehicleRepository
import org.nighthawklabs.telemetry.location.LocationTracker
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdResponseParser
import org.nighthawklabs.telemetry.service.ObdPollingService
import org.nighthawklabs.telemetry.ui.theme.TelemetryTheme
import org.nighthawklabs.telemetry.viewmodel.ObdViewModel

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Re-check permissions when user taps connect
        }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account?.idToken
                    if (idToken != null) {
                        firebaseAuthWithGoogle(idToken)
                    }
                } catch (e: ApiException) {
                    Log.w(TAG, "Google sign in failed", e)
                    viewModel.showError("Google Sign-In failed")
                }
            }
        }

    private val viewModel: ObdViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as ObdTelemetryApplication
                val db = app.database
                
                val telemetryRepository = TelemetryRepository(db.telemetryDao())
                val vehicleRepository = VehicleRepository(db.vehicleDao())

                val connectionManager = ObdConnectionManager(applicationContext)
                val executor = ObdCommandExecutor(connectionManager)
                val parser = ObdResponseParser()
                
                val locationTracker = LocationTracker(applicationContext)
                
                val pollingService = ObdPollingService(
                    context = applicationContext,
                    repository = telemetryRepository,
                    locationTracker = locationTracker,
                    externalScope = lifecycleScope
                )

                return ObdViewModel(
                    pollingService = pollingService,
                    telemetryRepository = telemetryRepository,
                    vehicleRepository = vehicleRepository,
                    connectionManager = connectionManager,
                    commandExecutor = executor,
                    responseParser = parser
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            TelemetryTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onConnectClick = {
                        if (ensurePermissions()) {
                            showDevicePicker()
                        }
                    },
                    onTripHistoryClick = {
                        startActivity(Intent(this, TripHistoryActivity::class.java))
                    },
                    onSyncClick = {
                        viewModel.syncNow(applicationContext)
                    },
                    onIceDryRunClick = {
                        viewModel.connectWithIceSimulator(includeAggressivePhase = true)
                    },
                    onEvDryRunClick = {
                        viewModel.connectWithEvSimulator()
                    },
                    onLoginClick = {
                        startGoogleLogin()
                    },
                    onLogoutClick = {
                        handleLogout()
                    }
                )
            }
        }
    }

    private fun startGoogleLogin() {
        val clientIdResId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val clientId = if (clientIdResId != 0) getString(clientIdResId) else null
        
        if (clientId == null) {
            viewModel.showError("Google Sign-In is not configured correctly.")
            Log.e(TAG, "Missing default_web_client_id. Please add SHA-1 and configure Google Sign-In in Firebase Console.")
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(clientId)
            .requestEmail()
            .build()
        
        val client = GoogleSignIn.getClient(this, gso)
        
        // Force account picker by signing out of the client before starting the intent
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun handleLogout() {
        // 1. Sign out from Firebase
        FirebaseAuth.getInstance().signOut()
        
        // 2. Also sign out from Google to clear the account cache
        val clientIdResId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val clientId = if (clientIdResId != 0) getString(clientIdResId) else null
        
        if (clientId != null) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(clientId)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
                viewModel.showMessage("Logged out successfully")
                Log.i(TAG, "Logged out from Firebase and Google.")
            }
        } else {
            viewModel.showMessage("Logged out from Firebase")
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        lifecycleScope.launch {
            try {
                val authResult = FirebaseAuth.getInstance().signInWithCredential(credential).await()
                val user = authResult.user
                viewModel.showMessage("Logged in as ${user?.email}")
                Log.i(TAG, "Logged in with Google: uid=${user?.uid}")
            } catch (e: Exception) {
                Log.e(TAG, "Firebase auth failed", e)
                viewModel.showError("Authentication failed")
            }
        }
    }

    private fun ensurePermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions += Manifest.permission.BLUETOOTH_CONNECT
            requiredPermissions += Manifest.permission.BLUETOOTH_SCAN
        } else {
            requiredPermissions += Manifest.permission.BLUETOOTH
            requiredPermissions += Manifest.permission.BLUETOOTH_ADMIN
        }

        requiredPermissions += Manifest.permission.ACCESS_FINE_LOCATION
        requiredPermissions += Manifest.permission.ACCESS_COARSE_LOCATION

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun showDevicePicker() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            viewModel.showError("Bluetooth not supported")
            return
        }

        if (!adapter.isEnabled) {
            viewModel.showError("Please enable Bluetooth")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ensurePermissions()
            return
        }

        val bondedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
        if (bondedDevices.isEmpty()) {
            viewModel.showMessage("No paired OBD devices found")
            return
        }

        val deviceList = bondedDevices.toList()
        val names = deviceList.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select OBD2 Device")
            .setItems(names) { dialog, which ->
                val device = deviceList[which]
                viewModel.connectToDevice(device)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
