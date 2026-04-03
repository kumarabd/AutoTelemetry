package org.nighthawklabs.telemetry.domain

enum class VehicleAlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class VehicleAlertType {
    COOLANT_OVERHEAT,
    HIGH_RPM,
    IDLE_ANOMALY,
    BATTERY_OVERHEAT
}

data class VehicleAlert(
    val alertId: String,
    val type: VehicleAlertType,
    val timestamp: Long,
    val message: String,
    val severity: VehicleAlertSeverity
)
