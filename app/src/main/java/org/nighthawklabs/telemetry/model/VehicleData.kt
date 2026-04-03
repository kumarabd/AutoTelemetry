package org.nighthawklabs.telemetry.model

import org.nighthawklabs.telemetry.obd.PidReading

/**
 * Base interface for vehicle telemetry data.
 * Contains fields common to all vehicle types.
 *
 * [readings] carries every PID that was successfully polled in this cycle,
 * keyed by the OBD command string (e.g. "010C").  This replaces the old
 * hardcoded named fields for vehicle-type-specific data, allowing any number
 * of PIDs to flow through the pipeline without changing the model.
 *
 * The typed convenience properties (speed, rpm, soc, etc.) are kept so that
 * existing consumers continue to compile without changes.
 */
interface VehicleData {
    val speed: Int?
    val timestamp: Long
    val vehicleId: String?

    /** Full map of all polled PIDs for this cycle. Key = OBD command e.g. "010D". */
    val readings: Map<String, PidReading>
}

/**
 * Telemetry data for Internal Combustion Engine (ICE) vehicles.
 *
 * [rpm] and [coolantTemp] are populated from [readings] for convenience.
 * All other PID values are accessible via [readings].
 */
data class IceVehicleData(
    override val speed: Int?,
    override val timestamp: Long,
    override val vehicleId: String? = null,
    override val readings: Map<String, PidReading> = emptyMap(),

    // Convenience accessors — sourced from readings when available
    val rpm: Int?          = readings["010C"]?.value?.toInt(),
    val coolantTemp: Int?  = readings["0105"]?.value?.toInt()
) : VehicleData

/**
 * Telemetry data for Electric Vehicles (EV).
 *
 * [soc] and [batteryTemp] are populated from [readings] for convenience.
 * All other PID values are accessible via [readings].
 */
data class EvVehicleData(
    override val speed: Int?,
    override val timestamp: Long,
    override val vehicleId: String? = null,
    override val readings: Map<String, PidReading> = emptyMap(),

    // Convenience accessors — sourced from readings when available
    val soc: Int?          = readings["015B"]?.value?.toInt(),
    val batteryTemp: Int?  = readings["015A"]?.value?.toInt()
) : VehicleData
