package org.nighthawklabs.telemetry.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TelemetryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TelemetryEntity>)

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(): TelemetryEntity?

    @Query(
        """
        SELECT * FROM telemetry
        WHERE syncStatus IN (:pending, :failed)
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingRecords(
        limit: Int,
        pending: String = "PENDING",
        failed: String = "FAILED"
    ): List<TelemetryEntity>

    @Query("UPDATE telemetry SET syncStatus = :status, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<String>, status: String, updatedAt: Long)

    suspend fun markRecordsSyncing(ids: List<String>, updatedAt: Long) =
        updateSyncStatus(ids, "SYNCING", updatedAt)

    suspend fun markRecordsSynced(ids: List<String>, updatedAt: Long) =
        updateSyncStatus(ids, "SYNCED", updatedAt)

    suspend fun markRecordsFailed(ids: List<String>, updatedAt: Long) =
        updateSyncStatus(ids, "FAILED", updatedAt)

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestTelemetry(): Flow<TelemetryEntity?>

    @Query("DELETE FROM telemetry WHERE syncStatus = 'SYNCED' AND timestamp < :timestamp")
    suspend fun deleteSyncedOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM telemetry WHERE syncStatus IN ('PENDING', 'FAILED')")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        UPDATE telemetry
        SET syncStatus = 'FAILED', updatedAt = :now
        WHERE syncStatus = 'SYNCING' AND updatedAt < :staleBefore
        """
    )
    suspend fun resetStuckSyncing(now: Long, staleBefore: Long)

    @Query("SELECT * FROM telemetry WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getTelemetryForTrip(tripId: String): List<TelemetryEntity>
}

