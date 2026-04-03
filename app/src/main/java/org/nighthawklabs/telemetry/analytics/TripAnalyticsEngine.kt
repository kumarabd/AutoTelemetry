package org.nighthawklabs.telemetry.analytics

import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.domain.TripSummary

class TripAnalyticsEngine {

    fun computeSummary(tripId: String, records: List<TelemetryRecord>): TripSummary? {
        if (records.isEmpty()) return null

        val sorted = records.sortedBy { it.timestamp }
        val startTime = sorted.first().timestamp
        val endTime = sorted.last().timestamp
        val durationMillis = (endTime - startTime).coerceAtLeast(0L)

        var distanceKm = 0.0
        var totalSpeed = 0.0
        var speedSamples = 0
        var maxSpeed = Double.MIN_VALUE
        var minSpeed = Double.MAX_VALUE
        var idleTimeMillis = 0L

        for (i in 0 until sorted.size) {
            val current = sorted[i]
            val next = sorted.getOrNull(i + 1)
            val speed = (current.speed ?: 0).toDouble()

            totalSpeed += speed
            speedSamples++
            if (speed > maxSpeed) maxSpeed = speed
            if (speed < minSpeed) minSpeed = speed

            if (next != null) {
                val deltaMillis = (next.timestamp - current.timestamp).coerceAtLeast(0L)
                val hours = deltaMillis / 3_600_000.0
                distanceKm += speed * hours

                if ((current.speed ?: 0) == 0) {
                    idleTimeMillis += deltaMillis
                }
            }
        }

        val avgSpeed = if (speedSamples > 0) totalSpeed / speedSamples else 0.0

        return TripSummary(
            tripId = tripId,
            startTime = startTime,
            endTime = endTime,
            durationMillis = durationMillis,
            distanceKm = distanceKm,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = if (maxSpeed.isFinite()) maxSpeed else 0.0,
            minSpeedKmh = if (minSpeed.isFinite()) minSpeed else 0.0,
            recordCount = sorted.size,
            idleTimeMillis = idleTimeMillis
        )
    }
}
