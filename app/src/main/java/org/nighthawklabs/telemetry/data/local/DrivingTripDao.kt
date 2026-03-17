package org.nighthawklabs.telemetry.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DrivingTripDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrip(entity: DrivingTripEntity)

    @Update
    suspend fun updateTrip(entity: DrivingTripEntity)

    @Query("SELECT * FROM driving_trips WHERE tripId = :tripId LIMIT 1")
    suspend fun getTripById(tripId: String): DrivingTripEntity?

    @Query("SELECT * FROM driving_trips WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTrip(): DrivingTripEntity?

    @Query("SELECT * FROM driving_trips WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActiveTrip(): Flow<DrivingTripEntity?>

    @Query(
        """
        UPDATE driving_trips
        SET status = 'COMPLETED',
            endedAt = :endedAt,
            endLatitude = :endLat,
            endLongitude = :endLng,
            updatedAt = :updatedAt
        WHERE tripId = :tripId
        """
    )
    suspend fun completeTrip(
        tripId: String,
        endedAt: Long,
        endLat: Double?,
        endLng: Double?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE driving_trips
        SET status = 'INTERRUPTED',
            endedAt = :endedAt,
            updatedAt = :updatedAt
        WHERE tripId = :tripId
        """
    )
    suspend fun interruptTrip(
        tripId: String,
        endedAt: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE driving_trips
        SET totalRecordCount = totalRecordCount + :delta,
            updatedAt = :updatedAt
        WHERE tripId = :tripId
        """
    )
    suspend fun incrementRecordCount(
        tripId: String,
        delta: Int,
        updatedAt: Long
    )

    @Query("SELECT * FROM driving_trips ORDER BY startedAt DESC")
    suspend fun getTripHistory(): List<DrivingTripEntity>
}

