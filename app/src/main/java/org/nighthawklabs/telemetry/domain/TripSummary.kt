package org.nighthawklabs.telemetry.domain

data class TripSummary(
    val tripId: String,
    val startTime: Long,
    val endTime: Long?,
    val durationMillis: Long,
    val distanceKm: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val minSpeedKmh: Double,
    val recordCount: Int,
    val idleTimeMillis: Long
)

