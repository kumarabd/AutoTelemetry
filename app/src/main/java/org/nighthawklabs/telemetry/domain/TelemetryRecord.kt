package org.nighthawklabs.telemetry.domain

import org.nighthawklabs.telemetry.obd.PidReading

/**
 * Clean, polymorphic domain model for telemetry data.
 *
 * [readings] carries the full set of polled PID values for the cycle, keyed
 * by OBD command string (e.g. "010C").  The typed convenience fields (rpm,
 * soc, etc.) are derived from [readings] so existing consumers keep working.
 */
sealed class TelemetryRecord {
    abstract val id: String
    abstract val timestamp: Long
    abstract val vehicleId: String?
    abstract val tripId: String?
    abstract val speed: Int?
    abstract val latitude: Double?
    abstract val longitude: Double?
    abstract val ignitionOn: Boolean?
    abstract val source: String
    abstract val syncStatus: SyncStatus
    abstract val createdAt: Long
    abstract val updatedAt: Long
    abstract val vehicleType: String

    /** Full PID map for this poll cycle. Serialised to Firestore as a nested map. */
    abstract val readings: Map<String, PidReading>

    data class Ice(
        override val id: String,
        override val timestamp: Long,
        override val vehicleId: String?,
        override val tripId: String?,
        override val speed: Int?,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        override val ignitionOn: Boolean? = null,
        override val source: String = "obd_android_app",
        override val syncStatus: SyncStatus,
        override val createdAt: Long,
        override val updatedAt: Long,
        override val readings: Map<String, PidReading> = emptyMap(),

        // Convenience fields derived from readings (kept for backward-compat)
        val rpm: Int?         = readings["010C"]?.value?.toInt(),
        val coolantTemp: Int? = readings["0105"]?.value?.toInt()
    ) : TelemetryRecord() {
        override val vehicleType: String = "ICE"
    }

    data class Ev(
        override val id: String,
        override val timestamp: Long,
        override val vehicleId: String?,
        override val tripId: String?,
        override val speed: Int?,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        override val ignitionOn: Boolean? = null,
        override val source: String = "obd_android_app",
        override val syncStatus: SyncStatus,
        override val createdAt: Long,
        override val updatedAt: Long,
        override val readings: Map<String, PidReading> = emptyMap(),

        // Convenience fields derived from readings (kept for backward-compat)
        val soc: Int?          = readings["015B"]?.value?.toInt(),
        val batteryTemp: Int?  = readings["015A"]?.value?.toInt()
    ) : TelemetryRecord() {
        override val vehicleType: String = "EV"
    }
}
