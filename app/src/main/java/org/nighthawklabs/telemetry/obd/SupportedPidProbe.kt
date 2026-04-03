package org.nighthawklabs.telemetry.obd

import android.util.Log

private const val TAG = "SupportedPidProbe"

/**
 * Probes the vehicle's OBD adapter to discover which PIDs it actually supports,
 * then intersects that with the desired [PidRegistry] set for the vehicle type.
 *
 * OBD-II vehicles report their supported PIDs in four 32-PID bitmask responses:
 *
 *   0100 → PIDs 01-20
 *   0120 → PIDs 21-40
 *   0140 → PIDs 41-60
 *   0160 → PIDs 61-80
 *
 * Each response is a 4-byte bitmask.  Bit 31 of the last range indicates whether
 * the next range is also supported.  We walk all four ranges and build a set of
 * supported hex PID strings.
 *
 * PIDs that the vehicle does not report in the bitmask are silently dropped from
 * the polling list, avoiding "NO DATA" spam at runtime.
 */
class SupportedPidProbe(
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) {

    /**
     * Discovers which PIDs from [candidates] the connected vehicle actually supports.
     *
     * @param candidates  Full list of PidSpecs to consider (from PidRegistry).
     * @return            Filtered list containing only PIDs reported as supported
     *                    by the vehicle, in the same order as [candidates].
     *                    Falls back to returning [candidates] unchanged if all
     *                    probe commands fail (so polling can still proceed on
     *                    adapters that don't implement the bitmask correctly).
     */
    suspend fun filterSupported(candidates: List<PidSpec>): List<PidSpec> {
        val supported = mutableSetOf<String>()
        var anyProbeSucceeded = false

        // The four standard availability ranges
        val probeCommands = listOf("0100", "0120", "0140", "0160")

        for (probe in probeCommands) {
            val raw = commandExecutor.sendRawCommand(probe)
            if (raw.isBlank()) {
                Log.d(TAG, "Probe $probe returned blank — skipping range")
                continue
            }

            val bytes = responseParser.extractDataBytes(raw)
            if (bytes.size < 4) {
                Log.d(TAG, "Probe $probe returned only ${bytes.size} bytes — skipping range")
                continue
            }

            anyProbeSucceeded = true

            // Reconstruct 32-bit bitmask from the 4 bytes
            val mask: Long = (bytes[0].toLong() shl 24) or
                    (bytes[1].toLong() shl 16) or
                    (bytes[2].toLong() shl 8) or
                    bytes[3].toLong()

            // Determine the PID base from the probe command: 0100→0, 0120→0x20, etc.
            val base = probe.substring(2).toInt(16)

            for (bit in 0 until 32) {
                if ((mask shr (31 - bit)) and 1L == 1L) {
                    val pidValue = base + bit + 1
                    val pidHex = pidValue.toString(16).padStart(2, '0').uppercase()
                    supported.add(pidHex)
                }
            }

            Log.d(TAG, "Probe $probe: mask=0x${mask.toString(16).padStart(8, '0')}, " +
                    "found ${supported.size} supported PIDs so far")

            // If bit 0 of the 4th byte is 0 the next range is not supported
            if ((bytes[3] and 0x01) == 0) {
                Log.d(TAG, "Next range not advertised — stopping probe at $probe")
                break
            }
        }

        if (!anyProbeSucceeded) {
            Log.w(TAG, "All PID probes failed. Returning full candidate list as fallback.")
            return candidates
        }

        Log.i(TAG, "Vehicle supports ${supported.size} Mode-01 PIDs: $supported")

        val filtered = candidates.filter { spec ->
            // spec.pid is always 2 chars, e.g. "0C" for RPM
            spec.pid.uppercase() in supported
        }

        Log.i(TAG, "Filtered ${candidates.size} candidates → ${filtered.size} to poll: " +
                filtered.map { it.pid })

        return filtered
    }
}
