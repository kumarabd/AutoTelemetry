package org.nighthawklabs.telemetry.domain

data class DrivingTrip(
    val tripId: String,
    val vehicleId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: TripStatus,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val totalRecordCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

