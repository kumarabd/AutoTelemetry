package org.nighthawklabs.telemetry.analytics

import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.domain.VehicleAlert
import org.nighthawklabs.telemetry.domain.VehicleAlertSeverity
import org.nighthawklabs.telemetry.domain.VehicleAlertType
import java.util.UUID

class VehicleHealthAnalyzer {

    fun analyze(records: List<TelemetryRecord>): List<VehicleAlert> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedBy { it.timestamp }
        val alerts = mutableListOf<VehicleAlert>()

        // Coolant temperature > 110°C
        sorted.forEach { rec ->
            val temp = rec.coolantTemp
            if (temp != null && temp > 110) {
                alerts += VehicleAlert(
                    alertId = UUID.randomUUID().toString(),
                    type = VehicleAlertType.COOLANT_OVERHEAT,
                    timestamp = rec.timestamp,
                    message = "Engine coolant temperature high: $temp°C",
                    severity = VehicleAlertSeverity.CRITICAL
                )
            }
        }

        // High RPM warning (> 4500 sustained)
        var highRpmStart: TelemetryRecord? = null
        for (i in sorted.indices) {
            val rec = sorted[i]
            val rpm = rec.rpm ?: 0
            if (rpm > 4500) {
                if (highRpmStart == null) {
                    highRpmStart = rec
                }
            } else if (highRpmStart != null) {
                val duration = rec.timestamp - highRpmStart.timestamp
                if (duration >= 5_000L) {
                    alerts += VehicleAlert(
                        alertId = UUID.randomUUID().toString(),
                        type = VehicleAlertType.HIGH_RPM,
                        timestamp = rec.timestamp,
                        message = "High RPM sustained for ${(duration / 1000)}s",
                        severity = VehicleAlertSeverity.WARNING
                    )
                }
                highRpmStart = null
            }
        }

        // Idle anomaly: speed == 0 and rpm > 1500 for > 10 seconds
        var idleStart: TelemetryRecord? = null
        for (i in sorted.indices) {
            val rec = sorted[i]
            val speed = rec.speed ?: 0
            val rpm = rec.rpm ?: 0
            val isIdleAnomaly = speed == 0 && rpm > 1500

            if (isIdleAnomaly) {
                if (idleStart == null) {
                    idleStart = rec
                }
            } else if (idleStart != null) {
                val duration = rec.timestamp - idleStart.timestamp
                if (duration >= 10_000L) {
                    alerts += VehicleAlert(
                        alertId = UUID.randomUUID().toString(),
                        type = VehicleAlertType.IDLE_ANOMALY,
                        timestamp = rec.timestamp,
                        message = "High RPM while stopped for ${(duration / 1000)}s",
                        severity = VehicleAlertSeverity.WARNING
                    )
                }
                idleStart = null
            }
        }

        return alerts
    }
}

