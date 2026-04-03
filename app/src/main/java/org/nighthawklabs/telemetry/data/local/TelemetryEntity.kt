package org.nighthawklabs.telemetry.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject
import org.nighthawklabs.telemetry.domain.SyncStatus
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.obd.PidReading

/**
 * Room entity for persisting telemetry records locally.
 *
 * The [readingsJson] column stores the full PID readings map as a compact JSON
 * string so we don't need to alter the schema every time a new PID is added.
 * Format: {"010C":{"label":"Engine RPM","value":2400.0,"unit":"rpm","raw":"41 0C ..."},…}
 *
 * The typed convenience columns (rpm, coolantTemp, soc, batteryTemp) are kept
 * for backward-compatibility with existing queries and to avoid a destructive
 * migration; they are populated from the readings map when writing.
 *
 * Schema version bump: 5 → 6  (adds readingsJson column via MIGRATION_5_6).
 */
@Entity(
    tableName = "telemetry",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class TelemetryEntity(
    @PrimaryKey val id: String,

    // Common fields
    val timestamp: Long,
    val speed: Int?,
    val vehicleId: String?,
    val tripId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val ignitionOn: Boolean?,
    val source: String,
    val vehicleType: String,

    // Legacy typed columns (still populated for backward-compat)
    val rpm: Int?,
    val coolantTemp: Int?,
    val soc: Int?,
    val batteryTemp: Int?,

    // Full PID readings serialised as JSON — added in migration 5→6
    val readingsJson: String? = null,

    // Sync metadata
    val syncStatus: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    // -----------------------------------------------------------------------
    // Domain → Entity
    // -----------------------------------------------------------------------
    companion object {
        fun fromDomain(record: TelemetryRecord): TelemetryEntity = TelemetryEntity(
            id          = record.id,
            timestamp   = record.timestamp,
            speed       = record.speed,
            vehicleId   = record.vehicleId,
            tripId      = record.tripId,
            latitude    = record.latitude,
            longitude   = record.longitude,
            ignitionOn  = record.ignitionOn,
            source      = record.source,
            vehicleType = record.vehicleType,

            // Legacy convenience columns
            rpm         = (record as? TelemetryRecord.Ice)?.rpm,
            coolantTemp = (record as? TelemetryRecord.Ice)?.coolantTemp,
            soc         = (record as? TelemetryRecord.Ev)?.soc,
            batteryTemp = (record as? TelemetryRecord.Ev)?.batteryTemp,

            readingsJson = serializeReadings(record.readings),

            syncStatus  = record.syncStatus.name,
            createdAt   = record.createdAt,
            updatedAt   = record.updatedAt
        )

        // ------------------------------------------------------------------
        // JSON helpers
        // ------------------------------------------------------------------

        /**
         * Serialises a Map<command, PidReading> to a compact JSON string.
         * Returns null (stored as SQL NULL) when the map is empty.
         */
        fun serializeReadings(readings: Map<String, PidReading>): String? {
            if (readings.isEmpty()) return null
            return try {
                val obj = JSONObject()
                for ((cmd, reading) in readings) {
                    val r = JSONObject().apply {
                        put("label", reading.label)
                        if (reading.value != null) put("value", reading.value) else put("value", JSONObject.NULL)
                        put("unit", reading.unit)
                        put("raw", reading.raw)
                    }
                    obj.put(cmd, r)
                }
                obj.toString()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Deserialises the stored JSON string back to Map<command, PidReading>.
         * Returns an empty map on null input or any parse error.
         */
        fun deserializeReadings(json: String?): Map<String, PidReading> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                val result = mutableMapOf<String, PidReading>()
                for (cmd in obj.keys()) {
                    val r = obj.getJSONObject(cmd)
                    result[cmd] = PidReading(
                        label = r.optString("label", cmd),
                        value = if (r.isNull("value")) null else r.optDouble("value").let {
                            if (it.isNaN()) null else it
                        },
                        unit  = r.optString("unit", ""),
                        raw   = r.optString("raw", "")
                    )
                }
                result
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Entity → Domain
    // -----------------------------------------------------------------------
    fun toDomain(): TelemetryRecord {
        val readings = deserializeReadings(readingsJson)
        return when (vehicleType) {
            "ICE" -> TelemetryRecord.Ice(
                id          = id,
                timestamp   = timestamp,
                vehicleId   = vehicleId,
                tripId      = tripId,
                speed       = speed,
                latitude    = latitude,
                longitude   = longitude,
                ignitionOn  = ignitionOn,
                source      = source,
                syncStatus  = SyncStatus.valueOf(syncStatus),
                createdAt   = createdAt,
                updatedAt   = updatedAt,
                readings    = readings
            )
            "EV"  -> TelemetryRecord.Ev(
                id          = id,
                timestamp   = timestamp,
                vehicleId   = vehicleId,
                tripId      = tripId,
                speed       = speed,
                latitude    = latitude,
                longitude   = longitude,
                ignitionOn  = ignitionOn,
                source      = source,
                syncStatus  = SyncStatus.valueOf(syncStatus),
                createdAt   = createdAt,
                updatedAt   = updatedAt,
                readings    = readings
            )
            else  -> throw IllegalStateException("Unknown vehicle type: $vehicleType")
        }
    }
}
