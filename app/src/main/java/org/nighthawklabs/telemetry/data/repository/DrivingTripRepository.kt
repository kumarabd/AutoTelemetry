package org.nighthawklabs.telemetry.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.nighthawklabs.telemetry.data.local.DrivingTripDao
import org.nighthawklabs.telemetry.data.local.DrivingTripEntity
import org.nighthawklabs.telemetry.domain.DrivingTrip

private const val TAG = "DrivingTripRepository"

interface IDrivingTripRepository {
    suspend fun getTripHistory(): List<DrivingTrip>
    suspend fun getTripById(tripId: String): DrivingTrip?
    fun observeActiveTrip(): Flow<DrivingTrip?>
}

class DrivingTripRepository(
    private val dao: DrivingTripDao
) : IDrivingTripRepository {

    override suspend fun getTripHistory(): List<DrivingTrip> =
        try {
            dao.getTripHistory().map { it.toDomain() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load trip history", e)
            emptyList()
        }

    override suspend fun getTripById(tripId: String): DrivingTrip? =
        try {
            dao.getTripById(tripId)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load trip $tripId", e)
            null
        }

    override fun observeActiveTrip(): Flow<DrivingTrip?> =
        dao.observeActiveTrip().map { it?.toDomain() }
}

