package org.nighthawklabs.telemetry.domain

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val vehicleType: VehicleType = VehicleType.UNKNOWN) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    enum class VehicleType {
        ICE, EV, HYBRID, UNKNOWN
    }
}
