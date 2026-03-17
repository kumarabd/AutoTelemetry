package org.nighthawklabs.telemetry.obd

import org.nighthawklabs.telemetry.model.VehicleData

/**
 * Abstraction for OBD vehicle data. Implementations may use real Bluetooth OBD
 * or simulated readings for dry-run / testing.
 */
interface ObdDataSource {

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun readVehicleData(): VehicleData
}
