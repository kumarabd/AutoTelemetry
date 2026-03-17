package org.nighthawklabs.telemetry.obd

import android.bluetooth.BluetoothDevice
import android.util.Log
import org.nighthawklabs.telemetry.model.VehicleData

private const val TAG = "RealObdDataSource"

class RealObdDataSource(
    private val device: BluetoothDevice,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ObdDataSource {

    private var vehicleId: String? = null

    override suspend fun connect(): Boolean {
        val ok = connectionManager.connect(device)
        if (!ok) return false
        return try {
            commandExecutor.initializeObd()
            // Attempt to get VIN as vehicleId
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
        val rpmRaw = commandExecutor.sendCommand("010C")
        val speedRaw = commandExecutor.sendCommand("010D")
        val tempRaw = commandExecutor.sendCommand("0105")

        val rpm = responseParser.parseRpm(rpmRaw)
        val speed = responseParser.parseSpeed(speedRaw)
        val coolantTemp = responseParser.parseCoolantTemp(tempRaw)

        return VehicleData(
            rpm = rpm,
            speed = speed,
            coolantTemp = coolantTemp,
            timestamp = System.currentTimeMillis(),
            vehicleId = vehicleId
        )
    }
}
