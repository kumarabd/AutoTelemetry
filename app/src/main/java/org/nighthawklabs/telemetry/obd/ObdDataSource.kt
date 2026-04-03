package org.nighthawklabs.telemetry.obd

import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.model.VehicleMetadata
import org.nighthawklabs.telemetry.domain.ConnectionState

/**
 * Abstraction for OBD vehicle data. Implementations may use real Bluetooth OBD
 * or simulated readings for dry-run / testing.
 */
interface ObdDataSource {

    val vehicleType: ConnectionState.VehicleType
    
    val vehicleId: String

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun readVehicleData(): VehicleData
    
    suspend fun getMetadata(): VehicleMetadata
}
