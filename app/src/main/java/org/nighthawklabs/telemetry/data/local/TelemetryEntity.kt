package org.nighthawklabs.telemetry.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.nighthawklabs.telemetry.domain.SyncStatus
import org.nighthawklabs.telemetry.domain.TelemetryRecord

@Entity(
    tableName = "telemetry",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class TelemetryEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val rpm: Int?,
    val speed: Int?,
    val coolantTemp: Int?,
    val soc: Int? = null,
    val batteryTemp: Int? = null,
    val latitude: Double?,
    val longitude: Double?,
    val ignitionOn: Boolean?,
    val vehicleId: String?,
    val tripId: String?,
    val source: String,
    val syncStatus: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): TelemetryRecord =
        TelemetryRecord(
            id = id,
            timestamp = timestamp,
            rpm = rpm,
            speed = speed,
            coolantTemp = coolantTemp,
            soc = soc,
            batteryTemp = batteryTemp,
            latitude = latitude,
            longitude = longitude,
            ignitionOn = ignitionOn,
            vehicleId = vehicleId,
            tripId = tripId,
            source = source,
            syncStatus = SyncStatus.valueOf(syncStatus),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        fun fromDomain(record: TelemetryRecord): TelemetryEntity =
            TelemetryEntity(
                id = record.id,
                timestamp = record.timestamp,
                rpm = record.rpm,
                speed = record.speed,
                coolantTemp = record.coolantTemp,
                soc = record.soc,
                batteryTemp = record.batteryTemp,
                latitude = record.latitude,
                longitude = record.longitude,
                ignitionOn = record.ignitionOn,
                vehicleId = record.vehicleId,
                tripId = record.tripId,
                source = record.source,
                syncStatus = record.syncStatus.name,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt
            )
    }
}
