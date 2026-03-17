package org.nighthawklabs.telemetry.domain

data class DrivingSessionInfo(
    val tripId: String,
    val vehicleId: String?,
    val startedAt: Long
)

