package org.nighthawklabs.telemetry.obd.ice

import android.bluetooth.BluetoothDevice
import android.util.Log
import org.nighthawklabs.telemetry.model.IceVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.obd.ObdResponseParser

private const val TAG = "IceObdDataSource"

/**
 * Implementation of ObdDataSource for Internal Combustion Engine (ICE) vehicles.
 */
class IceObdDataSource(
    private val device: BluetoothDevice,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val baseParser: ObdResponseParser
) : ObdDataSource {

    private val iceParser = IceObdParser(baseParser)
    private var vehicleId: String? = null

    override suspend fun connect(): Boolean {
        val ok = connectionManager.connect(device)
        if (!ok) return false
        return try {
            commandExecutor.initializeObd()
            vehicleId = try {
                val vinRaw = commandExecutor.sendCommand("0902")
                baseParser.parseVin(vinRaw)
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

        val rpm = iceParser.parseRpm(rpmRaw)
        val speed = baseParser.parseSpeed(speedRaw)
        val coolantTemp = iceParser.parseCoolantTemp(tempRaw)

        return IceVehicleData(
            rpm = rpm,
            speed = speed,
            coolantTemp = coolantTemp,
            timestamp = System.currentTimeMillis(),
            vehicleId = vehicleId
        )
    }
}
