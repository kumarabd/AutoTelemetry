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
    suspend fun saveReadingWithTrip(
        vehicleData: VehicleData,
        tripId: String?,
        latitude: Double? = null,
        longitude: Double? = null
    )
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
        private const val SYNCING_STALE_TIMEOUT_MS: Long = 1 * 60 * 1000
    }

    override suspend fun saveReading(vehicleData: VehicleData) {
        saveReadingWithTrip(vehicleData, null)
    }

    override suspend fun saveReadingWithTrip(
        vehicleData: VehicleData,
        tripId: String?,
        latitude: Double?,
        longitude: Double?
    ) {
        val now = System.currentTimeMillis()

        val record = when (vehicleData) {
            is IceVehicleData -> TelemetryRecord.Ice(
                id          = UUID.randomUUID().toString(),
                timestamp   = vehicleData.timestamp,
                vehicleId   = vehicleData.vehicleId,
                tripId      = tripId,
                speed       = vehicleData.speed,
                latitude    = latitude,
                longitude   = longitude,
                syncStatus  = SyncStatus.PENDING,
                createdAt   = now,
                updatedAt   = now,
                readings    = vehicleData.readings   // ← full PID map
            )
            is EvVehicleData -> TelemetryRecord.Ev(
                id          = UUID.randomUUID().toString(),
                timestamp   = vehicleData.timestamp,
                vehicleId   = vehicleData.vehicleId,
                tripId      = tripId,
                speed       = vehicleData.speed,
                latitude    = latitude,
                longitude   = longitude,
                syncStatus  = SyncStatus.PENDING,
                createdAt   = now,
                updatedAt   = now,
                readings    = vehicleData.readings   // ← full PID map
            )
            else -> throw IllegalArgumentException(
                "Unknown vehicle data type: ${vehicleData.javaClass.simpleName}"
            )
        }

        try {
            dao.insert(TelemetryEntity.fromDomain(record))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry reading", e)
        }
    }

    override fun observeLatestTelemetry(): Flow<TelemetryRecord?> =
        dao.observeLatestTelemetry().map { it?.toDomain() }

    override fun observePendingCount(): Flow<Int> =
        dao.observePendingCount()

    override suspend fun getPendingBatch(limit: Int): TelemetryBatch? {
        return try {
            val now = System.currentTimeMillis()
            val staleBefore = now - SYNCING_STALE_TIMEOUT_MS

            val resetCount = dao.resetStuckSyncing(now = now, staleBefore = staleBefore)
            if (resetCount > 0) {
                Log.i(TAG, "Reset $resetCount stale SYNCING records to FAILED.")
            }

            val entities = dao.getPendingRecords(limit)
            if (entities.isEmpty()) return null

            TelemetryBatch(
                batchId   = UUID.randomUUID().toString(),
                records   = entities.map { it.toDomain() },
                createdAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending batch", e)
            null
        }
    }

    override suspend fun markBatchSyncing(batch: TelemetryBatch) {
        runCatching { dao.markRecordsSyncing(batch.records.map { it.id }, System.currentTimeMillis()) }
            .onFailure { Log.e(TAG, "Failed to mark batch syncing", it) }
    }

    override suspend fun markBatchSynced(batch: TelemetryBatch) {
        runCatching { dao.markRecordsSynced(batch.records.map { it.id }, System.currentTimeMillis()) }
            .onFailure { Log.e(TAG, "Failed to mark batch synced", it) }
    }

    override suspend fun markBatchFailed(batch: TelemetryBatch) {
        runCatching { dao.markRecordsFailed(batch.records.map { it.id }, System.currentTimeMillis()) }
            .onFailure { Log.e(TAG, "Failed to mark batch failed", it) }
    }

    override suspend fun getTelemetryForTrip(tripId: String): List<TelemetryRecord> =
        runCatching { dao.getTelemetryForTrip(tripId).map { it.toDomain() } }
            .onFailure { Log.e(TAG, "Failed to load telemetry for trip $tripId", it) }
            .getOrDefault(emptyList())
}
