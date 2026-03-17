package org.nighthawklabs.telemetry.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.nighthawklabs.telemetry.domain.DrivingTrip
import org.nighthawklabs.telemetry.domain.TripStatus

@Entity(
    tableName = "driving_trips",
    indices = [
        Index(value = ["status"]),
        Index(value = ["startedAt"])
    ]
)
data class DrivingTripEntity(
    @PrimaryKey val tripId: String,
    val vehicleId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val totalRecordCount: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): DrivingTrip =
        DrivingTrip(
            tripId = tripId,
            vehicleId = vehicleId,
            startedAt = startedAt,
            endedAt = endedAt,
            status = TripStatus.valueOf(status),
            startLatitude = startLatitude,
            startLongitude = startLongitude,
            endLatitude = endLatitude,
            endLongitude = endLongitude,
            totalRecordCount = totalRecordCount,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        fun fromDomain(trip: DrivingTrip): DrivingTripEntity =
            DrivingTripEntity(
                tripId = trip.tripId,
                vehicleId = trip.vehicleId,
                startedAt = trip.startedAt,
                endedAt = trip.endedAt,
                status = trip.status.name,
                startLatitude = trip.startLatitude,
                startLongitude = trip.startLongitude,
                endLatitude = trip.endLatitude,
                endLongitude = trip.endLongitude,
                totalRecordCount = trip.totalRecordCount,
                createdAt = trip.createdAt,
                updatedAt = trip.updatedAt
            )
    }
}

