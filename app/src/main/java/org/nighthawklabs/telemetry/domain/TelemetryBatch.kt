package org.nighthawklabs.telemetry.domain

data class TelemetryBatch(
    val batchId: String,
    val records: List<TelemetryRecord>,
    val createdAt: Long
)

