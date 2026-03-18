package org.nighthawklabs.telemetry.obd.ev

import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.model.EvVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.obd.ObdDataSource
import kotlin.math.max
import kotlin.random.Random

class SimulatedEvDataSource : ObdDataSource {

    override val vehicleType: ConnectionState.VehicleType = ConnectionState.VehicleType.EV

    private var sessionStartMs: Long = 0L
    private var lastSoc: Double = 80.0
    private var lastBatteryTemp: Double = 25.0
    private val simulatedVehicleId = "SIMULATED_EV_001"

    override suspend fun connect(): Boolean {
        sessionStartMs = System.currentTimeMillis()
        lastSoc = 70.0 + Random.nextDouble() * 20.0
        lastBatteryTemp = 20.0 + Random.nextDouble() * 10.0
        return true
    }

    override suspend fun disconnect() {}

    override suspend fun readVehicleData(): VehicleData {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        
        // Simulate SoC slowly dropping
        lastSoc -= 0.001 
        
        // Simulate speed with some sine wave
        val speed = 30 + (kotlin.math.sin(elapsed * 0.1) * 20).toInt() + Random.nextInt(5)
        
        // Battery temp rises slightly with speed
        lastBatteryTemp += (speed / 100.0) * 0.01

        return EvVehicleData(
            speed = max(0, speed),
            soc = lastSoc.toInt(),
            batteryTemp = lastBatteryTemp.toInt(),
            timestamp = System.currentTimeMillis(),
            vehicleId = simulatedVehicleId
        )
    }
}
