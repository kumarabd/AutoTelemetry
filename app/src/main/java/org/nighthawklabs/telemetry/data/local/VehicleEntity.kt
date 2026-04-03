package org.nighthawklabs.telemetry.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.nighthawklabs.telemetry.model.VehicleMetadata

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val vehicleId: String,
    val vin: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    val fuelType: String?,
    val electrificationLevel: String?,
    val isEv: Boolean,
    val updatedAt: Long
) {
    fun toMetadata(): VehicleMetadata = VehicleMetadata(
        vin = vin,
        make = make,
        model = model,
        year = year,
        fuelType = fuelType,
        electrificationLevel = electrificationLevel,
        isEv = isEv
    )

    companion object {
        fun fromMetadata(vehicleId: String, metadata: VehicleMetadata): VehicleEntity =
            VehicleEntity(
                vehicleId = vehicleId,
                vin = metadata.vin,
                make = metadata.make,
                model = metadata.model,
                year = metadata.year,
                fuelType = metadata.fuelType,
                electrificationLevel = metadata.electrificationLevel,
                isEv = metadata.isEv,
                updatedAt = System.currentTimeMillis()
            )
    }
}
