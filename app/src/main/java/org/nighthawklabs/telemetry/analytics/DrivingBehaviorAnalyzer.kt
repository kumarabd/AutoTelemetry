package org.nighthawklabs.telemetry.analytics

import org.nighthawklabs.telemetry.domain.DrivingEvent
import org.nighthawklabs.telemetry.domain.DrivingEventSeverity
import org.nighthawklabs.telemetry.domain.DrivingEventType
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import java.util.UUID

class DrivingBehaviorAnalyzer {

    fun analyze(tripId: String, records: List<TelemetryRecord>): List<DrivingEvent> {
        if (records.size < 2) return emptyList()
        val sorted = records.sortedBy { it.timestamp }
        val events = mutableListOf<DrivingEvent>()

        // Harsh acceleration / hard braking (speed delta over 3s)
        var windowStartIndex = 0
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val windowStart = sorted[windowStartIndex]

            while (current.timestamp - windowStart.timestamp > 3_000L && windowStartIndex < i) {
                windowStartIndex++
            }
            val base = sorted[windowStartIndex]
            val baseSpeed = base.speed ?: 0
            val currentSpeed = current.speed ?: 0
            val delta = currentSpeed - baseSpeed

            if (delta > 20) {
                events += DrivingEvent(
                    eventId = UUID.randomUUID().toString(),
                    tripId = tripId,
                    type = DrivingEventType.HARSH_ACCELERATION,
                    timestamp = current.timestamp,
                    severity = if (delta > 40) DrivingEventSeverity.HIGH else DrivingEventSeverity.MEDIUM,
                    value = delta.toDouble()
                )
            } else if (delta < -20) {
                val magnitude = -delta
                events += DrivingEvent(
                    eventId = UUID.randomUUID().toString(),
                    tripId = tripId,
                    type = DrivingEventType.HARD_BRAKING,
                    timestamp = current.timestamp,
                    severity = if (magnitude > 40) DrivingEventSeverity.HIGH else DrivingEventSeverity.MEDIUM,
                    value = magnitude.toDouble()
                )
            }
        }

        // High RPM events (> 4000 for > 5s)
        var highRpmStart: TelemetryRecord? = null
        for (i in sorted.indices) {
            val rec = sorted[i]
            val rpm = rec.rpm ?: 0
            if (rpm > 4000) {
                if (highRpmStart == null) {
                    highRpmStart = rec
                }
            } else if (highRpmStart != null) {
                val duration = rec.timestamp - highRpmStart.timestamp
                if (duration >= 5_000L) {
                    events += DrivingEvent(
                        eventId = UUID.randomUUID().toString(),
                        tripId = tripId,
                        type = DrivingEventType.HIGH_RPM,
                        timestamp = rec.timestamp,
                        severity = DrivingEventSeverity.MEDIUM,
                        value = rpm.toDouble()
                    )
                }
                highRpmStart = null
            }
        }

        return events
    }
}

