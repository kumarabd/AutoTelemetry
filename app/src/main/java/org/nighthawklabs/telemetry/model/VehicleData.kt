package org.nighthawklabs.telemetry.model

/**
 * Base interface for vehicle telemetry data.
 * Contains fields common to all vehicle types.
 */
interface VehicleData {
    val speed: Int?
    val timestamp: Long
    val vehicleId: String?
}

/**
 * Telemetry data specific to Internal Combustion Engine (ICE) vehicles.
 */
data class IceVehicleData(
    override val speed: Int?,
    override val timestamp: Long,
    override val vehicleId: String? = null,
    val rpm: Int?,
    val coolantTemp: Int?
) : VehicleData

/**
 * Telemetry data specific to Electric Vehicles (EV).
 * Placeholder for future expansion.
 */
data class EvVehicleData(
    override val speed: Int?,
    override val timestamp: Long,
    override val vehicleId: String? = null,
    val soc: Int?, // State of Charge
    val batteryTemp: Int?
) : VehicleData
