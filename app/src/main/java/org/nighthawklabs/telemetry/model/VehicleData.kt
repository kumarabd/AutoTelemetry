package org.nighthawklabs.telemetry.model

data class VehicleData(
    val rpm: Int?,
    val speed: Int?,
    val coolantTemp: Int?,
    val timestamp: Long,
    val vehicleId: String? = null
)
