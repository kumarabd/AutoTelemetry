package org.nighthawklabs.telemetry.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nighthawklabs.telemetry.data.repository.ITelemetryRepository
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.viewmodel.ConnectionState

private const val TAG = "ObdPollingService"

class ObdPollingService(
    private val repository: ITelemetryRepository,
    private val externalScope: CoroutineScope
) {

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var pollingJob: Job? = null
    private var currentDataSource: ObdDataSource? = null

    fun connectAndStart(dataSource: ObdDataSource) {
        if (pollingJob != null) return

        _connectionState.value = ConnectionState.Connecting
        currentDataSource = dataSource

        pollingJob = externalScope.launch(Dispatchers.IO) {
            try {
                val connected = dataSource.connect()
                if (!connected) {
                    _connectionState.value = ConnectionState.Error("Failed to connect")
                    return@launch
                }

                _connectionState.value = ConnectionState.Connected

                while (true) {
                    try {
                        val vehicleData = dataSource.readVehicleData()
                        repository.saveReading(vehicleData)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Error during OBD polling", e)
                        _connectionState.value = ConnectionState.Error("Polling error")
                        break
                    }
                    delay(1_000L)
                }
            } finally {
                withContext(NonCancellable) {
                    dataSource.disconnect()
                }
                _connectionState.value = ConnectionState.Disconnected
                currentDataSource = null
                pollingJob = null
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
    }
}
