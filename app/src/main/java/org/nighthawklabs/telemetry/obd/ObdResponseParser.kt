package org.nighthawklabs.telemetry.obd

import android.util.Log

private const val TAG = "ObdResponseParser"

/**
 * Common OBD Response Parser for standard PIDs used by all vehicle types.
 */
class ObdResponseParser {

    /**
     * PID 010D (speed) - Common to all vehicles.
     */
    fun parseSpeed(response: String): Int? {
        return try {
            val bytes = extractDataBytes(response)
            if (bytes.isEmpty()) return null
            bytes[0]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse speed from: $response", e)
            null
        }
    }

    /**
     * Service 09 PID 02 (VIN) - Common to all vehicles.
     */
    fun parseVin(response: String): String? {
        return try {
            val vin = response.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && (it.startsWith("49 02") || it.startsWith("4902")) }
                .flatMap { line ->
                    val tokens = if (line.contains(" ")) line.split(" ") else line.chunked(2)
                    tokens.drop(3)
                }
                .map { it.toInt(16).toChar() }
                .joinToString("")
                .trim { it <= ' ' }

            if (vin.isBlank()) null else vin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VIN from: $response", e)
            null
        }
    }

    /**
     * Utility to extract raw data bytes from an OBD response string.
     */
    fun extractDataBytes(response: String): List<Int> {
        if (response.isBlank()) return emptyList()

        val cleaned = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .trim()

        val tokens = cleaned.split(" ")
            .filter { it.isNotBlank() }

        val hexChars = if (tokens.size == 1) {
            tokens.first().chunked(2)
        } else {
            tokens
        }

        if (hexChars.size <= 2) return emptyList()

        // Drop the echo and mode bytes (e.g., "41 0C")
        return hexChars.drop(2).mapNotNull {
            runCatching { it.toInt(16) }.getOrNull()
        }
    }
}
