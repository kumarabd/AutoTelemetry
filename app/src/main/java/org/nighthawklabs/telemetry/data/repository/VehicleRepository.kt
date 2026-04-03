package org.nighthawklabs.telemetry.data.repository

import android.util.Log
import org.nighthawklabs.telemetry.data.local.VehicleDao
import org.nighthawklabs.telemetry.data.local.VehicleEntity
import org.nighthawklabs.telemetry.model.VehicleMetadata

private const val TAG = "VehicleRepository"

interface IVehicleRepository {
    suspend fun saveVehicleMetadata(vehicleId: String, metadata: VehicleMetadata)
    suspend fun getVehicleMetadata(vehicleId: String): VehicleMetadata?
}

class VehicleRepository(
    private val dao: VehicleDao
) : IVehicleRepository {

    override suspend fun saveVehicleMetadata(vehicleId: String, metadata: VehicleMetadata) {
        try {
            val entity = VehicleEntity.fromMetadata(vehicleId, metadata)
            dao.insertVehicle(entity)
            Log.d(TAG, "Saved vehicle metadata for $vehicleId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vehicle metadata", e)
        }
    }

    override suspend fun getVehicleMetadata(vehicleId: String): VehicleMetadata? {
        return try {
            dao.getVehicleById(vehicleId)?.toMetadata()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get vehicle metadata", e)
            null
        }
    }
}
