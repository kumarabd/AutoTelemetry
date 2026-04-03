package org.nighthawklabs.telemetry.obd.ev

import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.model.EvVehicleData
import org.nighthawklabs.telemetry.model.VehicleData
import org.nighthawklabs.telemetry.model.VehicleMetadata
import org.nighthawklabs.telemetry.obd.ObdDataSource
import org.nighthawklabs.telemetry.obd.PidReading
import org.nighthawklabs.telemetry.obd.PidRegistry
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulated EV data source used for development and testing without a real OBD
 * adapter.  Generates plausible values for every PID in [PidRegistry.pidsForEv]
 * so the full readings pipeline can be exercised end-to-end.
 */
class SimulatedEvDataSource : ObdDataSource {

    override val vehicleType: ConnectionState.VehicleType = ConnectionState.VehicleType.EV
    override val vehicleId: String = "SIMULATED_EV_001"

    private var sessionStartMs = 0L
    private var lastSoc        = 80.0
    private var lastBattTemp   = 25.0
    private var odometer       = 12_500.0   // km

    override suspend fun connect(): Boolean {
        sessionStartMs = System.currentTimeMillis()
        lastSoc      = 70.0 + Random.nextDouble() * 20.0
        lastBattTemp = 20.0 + Random.nextDouble() * 10.0
        return true
    }

    override suspend fun disconnect() = Unit

    override suspend fun readVehicleData(): VehicleData {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000.0

        val speed       = max(0, 30 + (sin(elapsed * 0.1) * 20).toInt() + Random.nextInt(5))
        lastSoc        -= 0.001
        lastBattTemp   += (speed / 100.0) * 0.01
        odometer       += speed / 3600.0   // km per second at current speed

        val socPct       = lastSoc.coerceIn(0.0, 100.0)
        val hvVoltage    = 350.0 + Random.nextDouble() * 10.0
        val hvCurrent    = if (speed > 0) -(speed * 0.8 + Random.nextDouble() * 5.0) else 0.0
        val motorRpm     = speed * 8 + Random.nextInt(50)
        val throttle     = (speed / 120.0 * 100.0).coerceIn(0.0, 100.0)
        val regenLevel   = if (speed < 15) Random.nextDouble() * 30.0 else 0.0
        val hvacPower    = 800.0 + Random.nextDouble() * 400.0
        val estRange     = socPct / 100.0 * 350.0   // assume 350 km full charge
        val minCell      = 3.95 + Random.nextDouble() * 0.02
        val maxCell      = minCell + Random.nextDouble() * 0.02
        val ambientTemp  = 22.0 + Random.nextDouble() * 2.0
        val ctrlVoltage  = 12.4 + Random.nextDouble() * 0.3
        val accelD       = throttle * 0.9
        val accelE       = throttle * 0.85
        val baroPressure = 101.3 + Random.nextDouble() * 1.0

        // Build readings map using the same keys as PidRegistry
        val readings = buildMap<String, PidReading> {
            fun put(cmd: String, value: Double, unit: String, label: String) =
                put(cmd, PidReading(label = label, value = value, unit = unit))

            put("010D", speed.toDouble(),  "km/h", "Vehicle Speed")
            put("0111", throttle,          "%",    "Throttle Position")
            put("015B", socPct,            "%",    "State of Charge")
            put("015A", lastBattTemp,      "°C",   "Battery Pack Temperature")
            put("0101", hvVoltage,         "V",    "HV Battery Voltage")
            put("0102", hvCurrent,         "A",    "HV Battery Current")
            put("01A6", odometer,          "km",   "Odometer")
            put("014C", motorRpm.toDouble(),"rpm", "Traction Motor RPM")
            put("014D", regenLevel,        "%",    "Regen Braking Level")
            put("014E", hvacPower,         "W",    "HVAC Power Consumption")
            put("014F", estRange,          "km",   "Estimated Range")
            put("0150", 0.0,              "mode",  "Charging Status")
            put("0151", minCell,           "V",    "Min Cell Voltage")
            put("0152", maxCell,           "V",    "Max Cell Voltage")
            put("0146", ambientTemp,       "°C",   "Ambient Air Temperature")
            put("0142", ctrlVoltage,       "V",    "Control Module Voltage")
            put("0149", accelD,            "%",    "Accelerator Pedal Position D")
            put("014A", accelE,            "%",    "Accelerator Pedal Position E")
            put("0133", baroPressure,      "kPa",  "Barometric Pressure")
        }

        return EvVehicleData(
            speed     = speed,
            timestamp = System.currentTimeMillis(),
            vehicleId = vehicleId,
            readings  = readings
        )
    }

    override suspend fun getMetadata(): VehicleMetadata = VehicleMetadata(
        vin                  = "SIMEVTESLA32024",
        make                 = "Simulated",
        model                = "Model 3 (Sim)",
        year                 = 2024,
        fuelType             = "Electric",
        electrificationLevel = "Battery Electric Vehicle (BEV)",
        isEv                 = true
    )
}
