package org.nighthawklabs.telemetry.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import org.nighthawklabs.telemetry.ObdTelemetryApplication
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
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

    private val viewModel: ObdViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as ObdTelemetryApplication
                val db = app.database
                val telemetryDao = db.telemetryDao()
                val repository = TelemetryRepository(telemetryDao)

                val connectionManager = ObdConnectionManager(applicationContext)
                val executor = ObdCommandExecutor(connectionManager)
                val parser = ObdResponseParser()
                val pollingService = ObdPollingService(
                    context = applicationContext,
                    repository = repository,
                    externalScope = lifecycleScope
                )

                return ObdViewModel(
                    pollingService = pollingService,
                    repository = repository,
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
                    }
                )
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
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
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
            Log.w(TAG, "No paired Bluetooth devices found")
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
