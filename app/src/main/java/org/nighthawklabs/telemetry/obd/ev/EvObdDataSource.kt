package org.nighthawklabs.telemetry.obd.ev

import android.bluetooth.BluetoothDevice
import android.util.Log
import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.model.EvVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.model.VehicleMetadata
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.obd.ObdResponseParser
import org.nighthawklabs.telemetry.obd.PidReading
import org.nighthawklabs.telemetry.obd.PidRegistry
import org.nighthawklabs.telemetry.obd.PidSpec
import org.nighthawklabs.telemetry.obd.SupportedPidProbe

private const val TAG = "EvObdDataSource"

/**
 * OBD data source for Battery Electric Vehicles.
 *
 * On first [connect] it probes the adapter's supported-PID bitmasks (0100/0120/
 * 0140/0160) and intersects the result with [PidRegistry.pidsForEv].  Only PIDs
 * the vehicle actually advertises are polled on every [readVehicleData] call,
 * which eliminates "NO DATA" churn and keeps the poll cycle fast.
 *
 * All polled values are returned in [EvVehicleData.readings] keyed by OBD command
 * string so the full set flows through to Firebase without any model changes.
 */
class EvObdDataSource(
    private val device: BluetoothDevice,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ObdDataSource {

    override val vehicleType: ConnectionState.VehicleType = ConnectionState.VehicleType.EV

    private var _vehicleId: String = device.address
    override val vehicleId: String get() = _vehicleId

    /** Resolved after connect(); contains only PIDs the vehicle supports. */
    private var activePids: List<PidSpec> = emptyList()

    private val pidProbe = SupportedPidProbe(commandExecutor, responseParser)

    override suspend fun connect(): Boolean {
        val ok = connectionManager.connect(device)
        if (!ok) return false
        return try {
            commandExecutor.initializeObd()

            // Read VIN
            val vinRaw = commandExecutor.sendRawCommand("0902")
            val vin = responseParser.parseVin(vinRaw)
            if (!vin.isNullOrBlank()) {
                _vehicleId = vin
                Log.i(TAG, "VIN identified: $vin")
            }

            // Discover supported PIDs
            val candidates = PidRegistry.pidsForEv()
            activePids = pidProbe.filterSupported(candidates)
            Log.i(TAG, "Active EV PIDs (${activePids.size}): ${activePids.map { "${it.pid}=${it.label}" }}")

            true
        } catch (e: Exception) {
            Log.e(TAG, "OBD init failed", e)
            connectionManager.disconnect()
            false
        }
    }

    override suspend fun disconnect() = connectionManager.disconnect()

    override suspend fun readVehicleData(): VehicleData {
        val readings = mutableMapOf<String, PidReading>()

        for (spec in activePids) {
            val raw = commandExecutor.sendRawCommand(spec.command)
            if (raw.isBlank()) continue

            val bytes = responseParser.extractDataBytes(raw)
            val value = if (bytes.size >= spec.minBytes) {
                try { spec.decode(bytes) } catch (e: Exception) {
                    Log.w(TAG, "Decode error for ${spec.command} (${spec.label}): ${e.message}")
                    null
                }
            } else null

            readings[spec.command] = PidReading(
                label = spec.label,
                value = value,
                unit  = spec.unit,
                raw   = raw
            )
        }

        // Speed from the dedicated speed PID for the typed field
        val speed = readings["010D"]?.value?.toInt()

        return EvVehicleData(
            speed      = speed,
            timestamp  = System.currentTimeMillis(),
            vehicleId  = vehicleId,
            readings   = readings
        )
    }

    override suspend fun getMetadata(): VehicleMetadata =
        VehicleMetadata(vin = if (vehicleId == device.address) "" else vehicleId, isEv = true)
}
