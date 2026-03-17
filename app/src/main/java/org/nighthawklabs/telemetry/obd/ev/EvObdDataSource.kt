package org.nighthawklabs.telemetry.obd.ev

import android.bluetooth.BluetoothDevice
import android.util.Log
import org.nighthawklabs.telemetry.model.EvVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.obd.ObdResponseParser

private const val TAG = "EvObdDataSource"

/**
 * Implementation of ObdDataSource for Electric Vehicles (EV).
 */
class EvObdDataSource(
    private val device: BluetoothDevice,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ObdDataSource {

    private val evParser = EvObdParser(responseParser)
    private var vehicleId: String? = null

    override suspend fun connect(): Boolean {
        val ok = connectionManager.connect(device)
        if (!ok) return false
        return try {
            commandExecutor.initializeObd()
            vehicleId = try {
                val vinRaw = commandExecutor.sendCommand("0902")
                responseParser.parseVin(vinRaw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch VIN, using device address as fallback", e)
                device.address
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "OBD init failed", e)
            connectionManager.disconnect()
            false
        }
    }

    override suspend fun disconnect() {
        connectionManager.disconnect()
    }

    override suspend fun readVehicleData(): VehicleData {
        // Example PIDs for EV
        val speedRaw = commandExecutor.sendCommand("010D")
        val socRaw = commandExecutor.sendCommand("015B")
        val batteryTempRaw = commandExecutor.sendCommand("015A")

        val speed = responseParser.parseSpeed(speedRaw)
        val soc = evParser.parseSoc(socRaw)
        val batteryTemp = evParser.parseBatteryTemp(batteryTempRaw)

        return EvVehicleData(
            speed = speed,
            soc = soc,
            batteryTemp = batteryTemp,
            timestamp = System.currentTimeMillis(),
            vehicleId = vehicleId
        )
    }
}
