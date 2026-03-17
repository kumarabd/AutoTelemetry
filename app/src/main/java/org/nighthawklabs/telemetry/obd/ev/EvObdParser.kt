package org.nighthawklabs.telemetry.obd.ev

import android.util.Log
import org.nighthawklabs.telemetry.obd.ObdResponseParser

private const val TAG = "EvObdParser"

/**
 * Parser for Electric Vehicle (EV) specific OBD PIDs.
 */
class EvObdParser(private val baseParser: ObdResponseParser) {

    /**
     * State of Charge (SoC) - Example PID 015B
     */
    fun parseSoc(response: String): Int? {
        return try {
            val bytes = baseParser.extractDataBytes(response)
            if (bytes.isEmpty()) return null
            (bytes[0] * 100) / 255
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SoC from: $response", e)
            null
        }
    }

    /**
     * Battery Temperature - Example PID 015A
     */
    fun parseBatteryTemp(response: String): Int? {
        return try {
            val bytes = baseParser.extractDataBytes(response)
            if (bytes.isEmpty()) return null
            bytes[0] - 40
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse battery temp from: $response", e)
            null
        }
    }
}
