package org.nighthawklabs.telemetry.service

import android.content.Context
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
import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.location.LocationTracker
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.sync.SyncManager

private const val TAG = "ObdPollingService"
private const val FLUSH_BATCH_SIZE = 50
private const val FLUSH_INTERVAL_MS = 60_000L // 1 minute

class ObdPollingService(
    private val context: Context,
    private val repository: ITelemetryRepository,
    private val locationTracker: LocationTracker,
    private val externalScope: CoroutineScope
) {

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var pollingJob: Job? = null
    private var currentDataSource: ObdDataSource? = null
    
    private var recordsSinceLastFlush = 0
    private var lastFlushTime = System.currentTimeMillis()

    // Add support for tracking current trip
    private var activeTripId: String? = null

    fun setActiveTrip(tripId: String?) {
        activeTripId = tripId
    }

    fun connectAndStart(dataSource: ObdDataSource) {
        if (pollingJob != null) return

        _connectionState.value = ConnectionState.Connecting
        currentDataSource = dataSource
        
        // Reset counters on new connection
        recordsSinceLastFlush = 0
        lastFlushTime = System.currentTimeMillis()

        pollingJob = externalScope.launch(Dispatchers.IO) {
            try {
                val connected = dataSource.connect()
                if (!connected) {
                    _connectionState.value = ConnectionState.Error("Failed to connect")
                    return@launch
                }

                _connectionState.value = ConnectionState.Connected(dataSource.vehicleType)
                
                // Start tracking location when polling starts
                locationTracker.startTracking()

                while (true) {
                    try {
                        val vehicleData = dataSource.readVehicleData()
                        val currentLocation = locationTracker.currentLocation.value
                        
                        // Pass activeTripId and GPS coordinates if available
                        repository.saveReadingWithTrip(
                            vehicleData = vehicleData,
                            tripId = activeTripId,
                            latitude = currentLocation?.latitude,
                            longitude = currentLocation?.longitude
                        )
                        
                        recordsSinceLastFlush++
                        val now = System.currentTimeMillis()
                        
                        if (recordsSinceLastFlush >= FLUSH_BATCH_SIZE || (now - lastFlushTime) >= FLUSH_INTERVAL_MS) {
                            Log.d(TAG, "Flush triggered: records=$recordsSinceLastFlush, interval=${now - lastFlushTime}ms")
                            SyncManager.triggerSync(context)
                            recordsSinceLastFlush = 0
                            lastFlushTime = now
                        }

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
                    locationTracker.stopTracking()
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
