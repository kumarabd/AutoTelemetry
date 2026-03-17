package org.nighthawklabs.telemetry.domain

data class TelemetryRecord(
    val id: String,
    val timestamp: Long,
    val rpm: Int?,
    val speed: Int?,
    val coolantTemp: Int?,
    val soc: Int? = null,
    val batteryTemp: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ignitionOn: Boolean? = null,
    val vehicleId: String? = null,
    val tripId: String? = null,
    val source: String = "obd_android_app",
    val syncStatus: SyncStatus,
    val createdAt: Long,
    val updatedAt: Long
)
