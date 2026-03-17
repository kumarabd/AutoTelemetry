package org.nighthawklabs.telemetry.domain

enum class DrivingEventType {
    HARSH_ACCELERATION,
    HARD_BRAKING,
    HIGH_RPM
}

enum class DrivingEventSeverity {
    LOW,
    MEDIUM,
    HIGH
}

data class DrivingEvent(
    val eventId: String,
    val tripId: String,
    val type: DrivingEventType,
    val timestamp: Long,
    val severity: DrivingEventSeverity,
    val value: Double
)

