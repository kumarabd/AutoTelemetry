package org.nighthawklabs.telemetry.obd.ice

import android.util.Log
import org.nighthawklabs.telemetry.obd.ObdResponseParser

private const val TAG = "IceObdParser"

/**
 * Parser for Internal Combustion Engine (ICE) specific OBD PIDs.
 */
class IceObdParser(private val baseParser: ObdResponseParser) {

    /**
     * PID 010C (RPM)
     */
    fun parseRpm(response: String): Int? {
        return try {
            val bytes = baseParser.extractDataBytes(response)
            if (bytes.size < 2) return null
            ((bytes[0] * 256) + bytes[1]) / 4
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RPM from: $response", e)
            null
        }
    }

    /**
     * PID 0105 (coolant temp)
     */
    fun parseCoolantTemp(response: String): Int? {
        return try {
            val bytes = baseParser.extractDataBytes(response)
            if (bytes.isEmpty()) return null
            bytes[0] - 40
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse coolant temp from: $response", e)
            null
        }
    }
}
