package org.nighthawklabs.telemetry.obd

import kotlin.math.max
import kotlin.math.min
import org.nighthawklabs.telemetry.model.IceVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import kotlin.random.Random

/**
 * Generates a realistic driving session in phases. Use for dry-run / analytics testing
 * without Bluetooth hardware.
 */
class SimulatedObdDataSource(
    private val includeAggressivePhase: Boolean = false
) : ObdDataSource {

    private var sessionStartMs: Long = 0L
    private var lastCoolantTemp: Double = 65.0
    private var lastSpeed: Double = 0.0
    private val simulatedVehicleId = "SIMULATED_VEHICLE_001"

    // Phase durations in ms (with slight randomness)
    private val phase1DurationMs: Long = (20 + Random.nextInt(11)) * 1000L
    private val phase2DurationMs: Long = (40 + Random.nextInt(21)) * 1000L
    private val phase3DurationMs: Long = (60 + Random.nextInt(61)) * 1000L
    private val phase4DurationMs: Long = (20 + Random.nextInt(11)) * 1000L
    private val phase5DurationMs: Long = if (includeAggressivePhase) 15_000L else 0L

    private val phase1End = phase1DurationMs
    private val phase2End = phase1End + phase2DurationMs
    private val phase3End = phase2End + phase3DurationMs
    private val phase4End = phase3End + phase4DurationMs
    private val phase5End = phase4End + phase5DurationMs

    override suspend fun connect(): Boolean {
        sessionStartMs = System.currentTimeMillis()
        lastCoolantTemp = 60.0 + Random.nextDouble() * 15.0
        lastSpeed = 0.0
        return true
    }

    override suspend fun disconnect() {
        // No-op for simulator
    }

    override suspend fun readVehicleData(): VehicleData {
        val elapsed = System.currentTimeMillis() - sessionStartMs
        val (rpm, speed, coolant) = computeValues(elapsed)
        lastCoolantTemp = coolant
        lastSpeed = speed.toDouble()

        return IceVehicleData(
            rpm = rpm,
            speed = speed.toInt(),
            coolantTemp = coolant.toInt(),
            timestamp = System.currentTimeMillis(),
            vehicleId = simulatedVehicleId
        )
    }

    private fun computeValues(elapsedMs: Long): Triple<Int, Int, Double> {
        val totalDuration = if (includeAggressivePhase) phase5End else phase4End
        val wrappedMs = if (totalDuration > 0) elapsedMs % totalDuration else 0L
        val elapsed = wrappedMs / 1000.0

        return when {
            wrappedMs < phase1End -> phase1Idle(elapsed)
            wrappedMs < phase2End -> phase2CityStart(elapsed)
            wrappedMs < phase3End -> phase3Cruising(elapsed)
            wrappedMs < phase4End -> phase4Braking(elapsed)
            includeAggressivePhase && wrappedMs < phase5End -> phase5Aggressive(elapsed)
            else -> phase1Idle(0.0) // loop restart
        }
    }

    private fun phase1Idle(elapsed: Double): Triple<Int, Int, Double> {
        val rpm = 750 + Random.nextInt(201)
        val speed = 0
        val coolant = (lastCoolantTemp + 0.1).coerceIn(60.0, 75.0)
        return Triple(rpm, speed, coolant)
    }

    private fun phase2CityStart(elapsed: Double): Triple<Int, Int, Double> {
        val phaseStart = phase1End / 1000.0
        val phaseLen = phase2DurationMs / 1000.0
        val t = ((elapsed - phaseStart) / phaseLen).coerceIn(0.0, 1.0)
        val speed = (t * 35).toInt() + Random.nextInt(3) - 1
        val rpm = 1200 + (t * 1300).toInt() + Random.nextInt(200) - 100
        val coolant = (lastCoolantTemp + 0.15).coerceIn(60.0, 90.0)
        return Triple(rpm.coerceIn(1000, 3000), max(0, speed), coolant)
    }

    private fun phase3Cruising(elapsed: Double): Triple<Int, Int, Double> {
        val speed = 45 + Random.nextInt(21) + (kotlin.math.sin(elapsed * 0.1) * 5).toInt()
        val rpm = 1800 + Random.nextInt(1001) + (kotlin.math.sin(elapsed * 0.15) * 200).toInt()
        val coolant = (lastCoolantTemp + 0.05).coerceIn(85.0, 95.0)
        return Triple(rpm.coerceIn(1500, 3500), speed.coerceIn(40, 70), coolant)
    }

    private fun phase4Braking(elapsed: Double): Triple<Int, Int, Double> {
        val phaseStart = phase3End / 1000.0
        val phaseLen = phase4DurationMs / 1000.0
        val t = ((elapsed - phaseStart) / phaseLen).coerceIn(0.0, 1.0)
        val speed = (lastSpeed * (1 - t)).toInt().coerceAtLeast(0)
        val rpm = 800 + Random.nextInt(150) + (t * 200).toInt()
        val coolant = (lastCoolantTemp - 0.05).coerceIn(80.0, 95.0)
        return Triple(rpm.coerceIn(700, 1200), speed, coolant)
    }

    private fun phase5Aggressive(elapsed: Double): Triple<Int, Int, Double> {
        val phaseStart = phase4End / 1000.0
        val phaseLen = phase5DurationMs / 1000.0
        val t = ((elapsed - phaseStart) / phaseLen).coerceIn(0.0, 1.0)

        return when {
            t < 0.2 -> {
                // Harsh acceleration: 0 -> 70 in ~3 seconds
                val speed = (t / 0.2 * 70).toInt() + Random.nextInt(5)
                val rpm = 2500 + (t / 0.2 * 2000).toInt() + Random.nextInt(500)
                Triple(rpm.coerceAtMost(5500), speed.coerceAtMost(80), lastCoolantTemp)
            }
            t < 0.4 -> {
                // Hard braking: 70 -> 5 in ~3 seconds
                val localT = (t - 0.2) / 0.2
                val speed = (70 * (1 - localT)).toInt() + Random.nextInt(3)
                val rpm = 1200 + Random.nextInt(400)
                Triple(rpm, max(0, speed), lastCoolantTemp)
            }
            t < 0.7 -> {
                // High RPM: > 4500 for several seconds
                val speed = 40 + Random.nextInt(25)
                val rpm = 4600 + Random.nextInt(800)
                Triple(rpm, speed, min(105.0, lastCoolantTemp + 1))
            }
            else -> {
                // Ease back to idle
                val localT = (t - 0.7) / 0.3
                val speed = (40 * (1 - localT)).toInt().coerceAtLeast(0)
                val rpm = 850 + Random.nextInt(150)
                Triple(rpm, speed, lastCoolantTemp)
            }
        }
    }
}
