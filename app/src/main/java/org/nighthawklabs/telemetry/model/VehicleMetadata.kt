package org.nighthawklabs.telemetry.model

data class VehicleMetadata(
    val vin: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val fuelType: String? = null,
    val electrificationLevel: String? = null,
    val isEv: Boolean = false
)
