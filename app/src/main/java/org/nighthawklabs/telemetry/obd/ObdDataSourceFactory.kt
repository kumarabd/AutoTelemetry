package org.nighthawklabs.telemetry.obd

import android.bluetooth.BluetoothDevice
import android.util.Log
import org.nighthawklabs.telemetry.obd.ev.EvObdDataSource
import org.nighthawklabs.telemetry.obd.ice.IceObdDataSource

private const val TAG = "ObdDataSourceFactory"

class ObdDataSourceFactory(
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) {

    /**
     * Detects vehicle type by attempting to read the VIN and looking up common EV patterns.
     */
    suspend fun createDataSource(device: BluetoothDevice): ObdDataSource {
        connectionManager.connect(device)
        return try {
            commandExecutor.initializeObd()
            val vinRaw = commandExecutor.sendCommand("0902")
            val vin = responseParser.parseVin(vinRaw) ?: ""
            
            // Heuristic for demo:
            // Tesla starts with 5YJ, 7SA, or LRW
            val isEv = vin.startsWith("5YJ") || vin.startsWith("7SA") || vin.startsWith("LRW")
            
            if (isEv) {
                Log.d(TAG, "Detected EV vehicle via VIN: $vin")
                EvObdDataSource(
                    device,
                    connectionManager,
                    commandExecutor,
                    responseParser
                )
            } else {
                Log.d(TAG, "Detected ICE vehicle (or fallback) via VIN: $vin")
                IceObdDataSource(
                    device,
                    connectionManager,
                    commandExecutor,
                    responseParser
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed, defaulting to ICE", e)
            IceObdDataSource(
                device,
                connectionManager,
                commandExecutor,
                responseParser
            )
        } finally {
            // We disconnect so the PollingService can start its own connection lifecycle
            connectionManager.disconnect()
        }
    }
}
