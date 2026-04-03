package org.nighthawklabs.telemetry.obd

/**
 * Describes a single OBD-II PID: how to request it, how to label it,
 * what unit its value is in, and how to decode the raw data bytes into
 * a numeric value.
 *
 * All formulas match the SAE J1979 / ISO 15031-5 specification.
 *
 * @param command  The raw OBD hex command string, e.g. "010C" for RPM.
 * @param label    Human-readable name, e.g. "Engine RPM".
 * @param unit     Unit string for display, e.g. "rpm", "km/h", "°C".
 * @param minBytes Minimum number of data bytes expected in the response.
 * @param decode   Lambda that converts the raw data bytes (already stripped
 *                 of mode/pid echo by ObdResponseParser) to a Double value,
 *                 or null if the response is malformed.
 */
data class PidSpec(
    val command: String,
    val label: String,
    val unit: String,
    val minBytes: Int = 1,
    val decode: (bytes: List<Int>) -> Double?
) {
    /**
     * The 2-character PID hex extracted from the command (bytes 3-4).
     * e.g. "010C" → "0C"
     */
    val pid: String get() = if (command.length >= 4) command.substring(2) else command
}

/**
 * A single decoded PID reading captured during a poll cycle.
 *
 * @param label Human-readable label (from PidSpec).
 * @param value Decoded numeric value (null if the vehicle returned NO DATA or the
 *              response could not be decoded).
 * @param unit  Unit string (from PidSpec).
 * @param raw   Original raw string returned by the OBD adapter, for debugging.
 */
data class PidReading(
    val label: String,
    val value: Double?,
    val unit: String,
    val raw: String = ""
)
