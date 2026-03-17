package org.nighthawklabs.telemetry.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import org.nighthawklabs.telemetry.R
import org.nighthawklabs.telemetry.data.local.AppDatabase
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdResponseParser
import org.nighthawklabs.telemetry.service.ObdPollingService
import org.nighthawklabs.telemetry.viewmodel.ConnectionState
import org.nighthawklabs.telemetry.viewmodel.ObdViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // No-op; we re-check permissions when user taps connect
        }

    private val viewModel: ObdViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = Room.databaseBuilder(
                    applicationContext,
                    AppDatabase::class.java,
                    "telemetry.db"
                ).addMigrations(AppDatabase.MIGRATION_1_2).build()
                val telemetryDao = db.telemetryDao()
                val repository = TelemetryRepository(telemetryDao)

                val connectionManager = ObdConnectionManager(this@MainActivity)
                val executor = ObdCommandExecutor(connectionManager)
                val parser = ObdResponseParser()
                val pollingService = ObdPollingService(
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
        setContentView(R.layout.activity_main)

        val btnConnect: Button = findViewById(R.id.btnConnect)
        val txtStatus: TextView = findViewById(R.id.txtConnectionStatus)
        val txtRpm: TextView = findViewById(R.id.txtRpm)
        val txtSpeed: TextView = findViewById(R.id.txtSpeed)
        val txtCoolant: TextView = findViewById(R.id.txtCoolantTemp)
        val txtPending: TextView = findViewById(R.id.txtPendingCount)
        val btnSyncNow: Button = findViewById(R.id.btnSyncNow)
        val txtSyncState: TextView = findViewById(R.id.txtSyncState)
        val btnTripHistory: Button = findViewById(R.id.btnTripHistory)
        val btnDryRun: Button = findViewById(R.id.btnDryRun)

        btnConnect.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            showDevicePicker()
        }

        btnSyncNow.setOnClickListener {
            viewModel.syncNow(applicationContext)
        }

        btnTripHistory.setOnClickListener {
            startActivity(android.content.Intent(this, TripHistoryActivity::class.java))
        }

        btnDryRun.setOnClickListener {
            viewModel.connectWithSimulator(includeAggressivePhase = true)
        }

        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        txtStatus.text = "Status: Disconnected"
                    }

                    is ConnectionState.Connecting -> {
                        txtStatus.text = "Status: Connecting..."
                    }

                    is ConnectionState.Connected -> {
                        txtStatus.text = "Status: Connected"
                    }

                    is ConnectionState.Error -> {
                        txtStatus.text = "Status: Error - ${state.message}"
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.latestTelemetry.collectLatest { data ->
                if (data == null) return@collectLatest
                txtRpm.text = "RPM: ${data.rpm ?: "-"}"
                txtSpeed.text = "Speed: ${data.speed ?: "-"} km/h"
                txtCoolant.text = "Coolant Temp: ${data.coolantTemp ?: "-"} °C"
            }
        }

        lifecycleScope.launch {
            viewModel.pendingRecordCount.collectLatest { count ->
                txtPending.text = "Pending records: $count"
            }
        }

        lifecycleScope.launch {
            viewModel.syncState.collectLatest { state ->
                txtSyncState.text = "Sync state: ${state.name.lowercase().replaceFirstChar { it.uppercase() }}"
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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select OBD2 Device")
            .setItems(names) { dialog, which ->
                val device = deviceList[which]
                connectToDevice(device)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "User selected device: ${device.name} - ${device.address}")
        viewModel.connectToDevice(device)
    }
}

