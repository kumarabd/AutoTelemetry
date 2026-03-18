package org.nighthawklabs.telemetry.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.nighthawklabs.telemetry.data.local.TelemetryDao
import org.nighthawklabs.telemetry.data.local.TelemetryEntity
import org.nighthawklabs.telemetry.domain.SyncStatus
import org.nighthawklabs.telemetry.domain.TelemetryBatch
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.model.EvVehicleData
import org.nighthawklabs.telemetry.model.IceVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import java.util.UUID

private const val TAG = "TelemetryRepository"

interface ITelemetryRepository {
    suspend fun saveReading(vehicleData: VehicleData)
    suspend fun saveReadingWithTrip(vehicleData: VehicleData, tripId: String?)
    fun observeLatestTelemetry(): Flow<TelemetryRecord?>
    fun observePendingCount(): Flow<Int>
    suspend fun getPendingBatch(limit: Int = 100): TelemetryBatch?
    suspend fun markBatchSyncing(batch: TelemetryBatch)
    suspend fun markBatchSynced(batch: TelemetryBatch)
    suspend fun markBatchFailed(batch: TelemetryBatch)
    suspend fun getTelemetryForTrip(tripId: String): List<TelemetryRecord>
}

class TelemetryRepository(
    private val dao: TelemetryDao
) : ITelemetryRepository {

    private companion object {
        // Reduced from 5 minutes to 1 minute.
        // If a sync batch takes more than 1 minute, it's likely crashed or stuck.
        private const val SYNCING_STALE_TIMEOUT_MS: Long = 1 * 60 * 1000 
    }

    override suspend fun saveReading(vehicleData: VehicleData) {
        saveReadingWithTrip(vehicleData, null)
    }

    override suspend fun saveReadingWithTrip(vehicleData: VehicleData, tripId: String?) {
        val now = System.currentTimeMillis()
        
        // Map polymorphic VehicleData to unified TelemetryRecord
        val record = TelemetryRecord(
            id = UUID.randomUUID().toString(),
            timestamp = vehicleData.timestamp,
            speed = vehicleData.speed,
            vehicleId = vehicleData.vehicleId,
            tripId = tripId,
            
            // Extract ICE fields if present
            rpm = (vehicleData as? IceVehicleData)?.rpm,
            coolantTemp = (vehicleData as? IceVehicleData)?.coolantTemp,
            
            // Extract EV fields if present
            soc = (vehicleData as? EvVehicleData)?.soc,
            batteryTemp = (vehicleData as? EvVehicleData)?.batteryTemp,
            
            latitude = null,
            longitude = null,
            ignitionOn = null,
            source = "obd_android_app",
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )

        try {
            dao.insert(TelemetryEntity.fromDomain(record))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry reading", e)
        }
    }

    override fun observeLatestTelemetry(): Flow<TelemetryRecord?> =
        dao.observeLatestTelemetry()
            .map { it?.toDomain() }

    override fun observePendingCount(): Flow<Int> =
        dao.observePendingCount()

    override suspend fun getPendingBatch(limit: Int): TelemetryBatch? {
        return try {
            val now = System.currentTimeMillis()
            val staleBefore = now - SYNCING_STALE_TIMEOUT_MS
            
            // Reset records stuck in SYNCING state for too long
            val resetCount = dao.resetStuckSyncing(now = now, staleBefore = staleBefore)
            if (resetCount > 0) {
                Log.i(TAG, "Reset $resetCount stale records from SYNCING to FAILED status.")
            }

            val entities = dao.getPendingRecords(limit)
            if (entities.isEmpty()) return null

            val records = entities.map { it.toDomain() }
            TelemetryBatch(
                batchId = UUID.randomUUID().toString(),
                records = records,
                createdAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending batch", e)
            null
        }
    }

    override suspend fun markBatchSyncing(batch: TelemetryBatch) {
        val ids = batch.records.map { it.id }
        try {
            val now = System.currentTimeMillis()
            dao.markRecordsSyncing(ids, now)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark batch syncing", e)
        }
    }

    override suspend fun markBatchSynced(batch: TelemetryBatch) {
        val ids = batch.records.map { it.id }
        try {
            val now = System.currentTimeMillis()
            dao.markRecordsSynced(ids, now)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark batch synced", e)
        }
    }

    override suspend fun markBatchFailed(batch: TelemetryBatch) {
        val ids = batch.records.map { it.id }
        try {
            val now = System.currentTimeMillis()
            dao.markRecordsFailed(ids, now)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark batch failed", e)
        }
    }

    override suspend fun getTelemetryForTrip(tripId: String): List<TelemetryRecord> =
        try {
            dao.getTelemetryForTrip(tripId).map { it.toDomain() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load telemetry for trip $tripId", e)
            emptyList()
        }
}
