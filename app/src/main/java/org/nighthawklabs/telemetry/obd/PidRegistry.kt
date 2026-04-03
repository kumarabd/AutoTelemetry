package org.nighthawklabs.telemetry.obd

/**
 * Catalogue of all standard SAE J1979 Mode 01 PIDs, plus common EV and ICE
 * extended PIDs.
 *
 * PIDs are grouped so callers can request exactly the set that applies to a
 * given vehicle type.  The [commonPids] list applies to every OBD-II vehicle;
 * [icePids] is added for combustion/hybrid vehicles; [evPids] is added for
 * battery electric vehicles.
 *
 * Decode formulas follow the official SAE J1979 / ISO 15031-5 specification.
 * Where a PID requires more than one data byte the formula is applied to the
 * first two (A, B) or four (A-D) bytes as per the standard.
 *
 * Reference: https://en.wikipedia.org/wiki/OBD-II_PIDs
 */
object PidRegistry {

    // -------------------------------------------------------------------------
    // Mode 01 PIDs common to all OBD-II vehicles
    // -------------------------------------------------------------------------
    val commonPids: List<PidSpec> = listOf(

        PidSpec("010D", "Vehicle Speed", "km/h", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.toDouble()
        },

        PidSpec("0111", "Throttle Position", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("0133", "Barometric Pressure", "kPa", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.toDouble()
        },

        PidSpec("012F", "Fuel Tank Level", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("0146", "Ambient Air Temperature", "°C", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a - 40).toDouble() }
        },

        PidSpec("015C", "Engine Oil Temperature", "°C", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a - 40).toDouble() }
        },

        PidSpec("0149", "Accelerator Pedal Position D", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("014A", "Accelerator Pedal Position E", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("0142", "Control Module Voltage", "V", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 1000.0
        },

        PidSpec("015E", "Engine Fuel Rate", "L/h", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 20.0
        }
    )

    // -------------------------------------------------------------------------
    // ICE / Hybrid specific PIDs (Mode 01)
    // -------------------------------------------------------------------------
    val icePids: List<PidSpec> = listOf(

        PidSpec("010C", "Engine RPM", "rpm", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 4.0
        },

        PidSpec("0105", "Engine Coolant Temperature", "°C", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a - 40).toDouble() }
        },

        PidSpec("010B", "Intake Manifold Pressure", "kPa", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.toDouble()
        },

        PidSpec("010F", "Intake Air Temperature", "°C", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a - 40).toDouble() }
        },

        PidSpec("0110", "MAF Air Flow Rate", "g/s", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 100.0
        },

        PidSpec("0104", "Calculated Engine Load", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("010E", "Timing Advance", "° before TDC", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a / 2.0) - 64.0 }
        },

        PidSpec("0114", "O2 Sensor 1 - Voltage", "V", minBytes = 2) { bytes ->
            bytes.getOrNull(0)?.let { a -> a / 200.0 }
        },

        PidSpec("012C", "Commanded EGR", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        PidSpec("012D", "EGR Error", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0 / 128.0) - 100.0 }
        },

        PidSpec("0130", "Warm-ups since Codes Cleared", "count", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.toDouble()
        },

        PidSpec("0131", "Distance since Codes Cleared", "km", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b).toDouble()
        },

        PidSpec("013C", "Catalyst Temperature Bank 1 S1", "°C", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            (((a * 256) + b) / 10.0) - 40.0
        },

        PidSpec("0143", "Absolute Load Value", "%", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) * 100.0 / 255.0
        },

        PidSpec("0144", "Commanded Air-Fuel Ratio", "ratio", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 32768.0 * 14.7
        },

        PidSpec("0145", "Relative Throttle Position", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        }
    )

    // -------------------------------------------------------------------------
    // EV / BEV specific PIDs
    // Many OEMs expose battery data on Mode 01 extended PIDs or proprietary PIDs.
    // These are the most commonly supported ones across Nissan Leaf, VW ID,
    // Hyundai Ioniq, Chevy Bolt, BMW i3, Renault Zoe and similar platforms.
    // -------------------------------------------------------------------------
    val evPids: List<PidSpec> = listOf(

        // State of Charge — SAE J1939 / ISO 15118 style, broadly supported
        PidSpec("015B", "State of Charge", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        // High-Voltage battery pack temperature
        PidSpec("015A", "Battery Pack Temperature", "°C", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a - 40).toDouble() }
        },

        // HV battery voltage (Mode 01 PID 0x01, extended — supported on many OBDs)
        PidSpec("0101", "HV Battery Voltage", "V", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 10.0
        },

        // HV battery current — signed, two-byte
        PidSpec("0102", "HV Battery Current", "A", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            val raw = (a * 256) + b
            // Signed 16-bit
            (if (raw > 32767) raw - 65536 else raw) / 10.0
        },

        // Odometer
        PidSpec("01A6", "Odometer", "km", minBytes = 4) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            val c = bytes.getOrNull(2) ?: return@PidSpec null
            val d = bytes.getOrNull(3) ?: return@PidSpec null
            ((a * 16777216L) + (b * 65536L) + (c * 256L) + d).toDouble() / 10.0
        },

        // Traction motor RPM — not in SAE standard but available via Mode 01 on many EVs
        PidSpec("014C", "Traction Motor RPM", "rpm", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b).toDouble()
        },

        // Regenerative braking power — if reported
        PidSpec("014D", "Regen Braking Level", "%", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.let { a -> (a * 100.0) / 255.0 }
        },

        // Cabin climate system power draw
        PidSpec("014E", "HVAC Power Consumption", "W", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b).toDouble()
        },

        // Remaining range estimate — some OEMs
        PidSpec("014F", "Estimated Range", "km", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b).toDouble()
        },

        // Charging status flag (0 = not charging, 1 = AC charging, 2 = DC fast charge)
        PidSpec("0150", "Charging Status", "mode", minBytes = 1) { bytes ->
            bytes.getOrNull(0)?.toDouble()
        },

        // Battery cell min/max voltage (for pack health monitoring)
        PidSpec("0151", "Min Cell Voltage", "V", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 1000.0
        },

        PidSpec("0152", "Max Cell Voltage", "V", minBytes = 2) { bytes ->
            val a = bytes.getOrNull(0) ?: return@PidSpec null
            val b = bytes.getOrNull(1) ?: return@PidSpec null
            ((a * 256) + b) / 1000.0
        }
    )

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the combined PID list for an EV (common + EV-specific). */
    fun pidsForEv(): List<PidSpec> = commonPids + evPids

    /** Returns the combined PID list for an ICE vehicle (common + ICE-specific). */
    fun pidsForIce(): List<PidSpec> = commonPids + icePids

    /** Returns the combined PID list for a hybrid (common + ICE + EV). */
    fun pidsForHybrid(): List<PidSpec> = commonPids + icePids + evPids

    /**
     * Looks up a PidSpec by its 2-character PID hex (case-insensitive).
     * e.g. lookupByPid("0C") returns the RPM spec.
     */
    fun lookupByPid(pid: String): PidSpec? =
        (commonPids + icePids + evPids).firstOrNull {
            it.pid.equals(pid, ignoreCase = true)
        }
}
