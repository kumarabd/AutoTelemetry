package org.nighthawklabs.telemetry.obd

import android.util.Log

private const val TAG = "ObdResponseParser"

class ObdResponseParser {

    /**
     * Expects a response for PID 010C (RPM), e.g. "41 0C 1A F8"
     * Formula: RPM = ((A * 256) + B) / 4
     */
    fun parseRpm(response: String): Int? {
        return try {
            val bytes = extractDataBytes(response)
            if (bytes.size < 2) return null
            val a = bytes[0]
            val b = bytes[1]
            ((a * 256) + b) / 4
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RPM from: $response", e)
            null
        }
    }

    /**
     * Expects a response for PID 010D (speed), e.g. "41 0D 3C"
     * Speed = A (km/h)
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
     * Expects a response for PID 0105 (coolant temp), e.g. "41 05 7B"
     * Temp (°C) = A - 40
     */
    fun parseCoolantTemp(response: String): Int? {
        return try {
            val bytes = extractDataBytes(response)
            if (bytes.isEmpty()) return null
            bytes[0] - 40
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse coolant temp from: $response", e)
            null
        }
    }

    /**
     * Expects a response for Service 09 PID 02 (VIN), which may be multi-line.
     * Example: 49 02 01 00 00 00 31 
     *          49 02 02 47 42 45 42 
     *          49 02 03 33 34 35 36 
     *          49 02 04 37 38 39 30 
     *          49 02 05 31 32 33 34 
     */
    fun parseVin(response: String): String? {
        return try {
            val vin = response.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && (it.startsWith("49 02") || it.startsWith("4902")) }
                .flatMap { line ->
                    val tokens = if (line.contains(" ")) line.split(" ") else line.chunked(2)
                    tokens.drop(3) // drop mode, PID, and line number
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
     * Normalizes response string and extracts data bytes after the mode+PID bytes.
     * Handles strings like "41 0C 1A F8" or "410C1AF8".
     */
    private fun extractDataBytes(response: String): List<Int> {
        if (response.isBlank()) return emptyList()

        val cleaned = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .trim()

        val tokens = cleaned.split(" ")
            .filter { it.isNotBlank() }

        val hexChars = if (tokens.size == 1) {
            tokens.first()
                .chunked(2)
        } else {
            tokens
        }

        if (hexChars.size <= 2) return emptyList()

        return hexChars
            .drop(2) // drop mode + PID bytes
            .mapNotNull {
                runCatching { it.toInt(16) }.getOrNull()
            }
    }
}
