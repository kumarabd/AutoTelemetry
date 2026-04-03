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
     * Handles both single-line and multi-line ISO 15765-4 responses.
     */
    fun parseVin(response: String): String? {
        Log.d(TAG, "Parsing VIN. Raw response: '$response'")
        if (response.isBlank() || response.contains("NO DATA", ignoreCase = true)) {
            Log.w(TAG, "VIN response is blank or contains NO DATA")
            return null
        }
        
        return try {
            // Clean the response: remove protocol markers (0: 1: etc.) and headers
            val hexString = response.lines()
                .map { it.trim() }
                .map { line ->
                    // Remove CAN frame index (e.g., "0:", "1:")
                    if (line.contains(":") && line.indexOf(":") < 3) {
                        line.substring(line.indexOf(":") + 1).trim()
                    } else {
                        line
                    }
                }
                .joinToString("") { it.replace(" ", "") }
            
            Log.d(TAG, "Cleaned hex string: $hexString")
            
            // Look for "4902" (Mode 09 PID 02 response)
            val searchPattern = "4902"
            val index = hexString.indexOf(searchPattern)
            if (index == -1) {
                Log.w(TAG, "Search pattern '4902' not found in response.")
                // Try to find if it just returned the raw VIN bytes without the mode/pid echo
                if (hexString.length >= 34) { 
                    val candidate = hexString.take(34)
                    val vin = hexToAscii(candidate)
                    Log.d(TAG, "Attempting raw hex-to-ascii conversion: $candidate -> $vin")
                    if (isValidVin(vin)) {
                        Log.i(TAG, "Found valid raw VIN: $vin")
                        return vin
                    }
                }
                return null
            }

            // The format is usually: 49 02 01 <vin_bytes> 
            val dataStart = index + 6 
            Log.d(TAG, "Data start index: $dataStart. Total length: ${hexString.length}")

            if (hexString.length < dataStart + 34) {
                Log.w(TAG, "Hex string too short to contain full VIN (need 34 chars from start index)")
                return null
            }
            
            val vinHex = hexString.substring(dataStart, dataStart + 34)
            val vin = hexToAscii(vinHex)
            Log.d(TAG, "Extracted VIN hex: $vinHex -> ASCII: $vin")
            
            if (isValidVin(vin)) {
                Log.i(TAG, "Successfully parsed valid VIN: $vin")
                vin
            } else {
                Log.w(TAG, "Parsed string is not a valid VIN: $vin")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during VIN parsing", e)
            null
        }
    }

    private fun hexToAscii(hex: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < hex.length) {
            try {
                val str = hex.substring(i, i + 2)
                output.append(str.toInt(16).toChar())
            } catch (e: Exception) {
                Log.v(TAG, "Skipping invalid hex pair at $i")
            }
            i += 2
        }
        return output.toString().trim()
    }

    private fun isValidVin(vin: String): Boolean {
        // VIN is exactly 17 characters, alphanumeric, no I, O, Q
        val valid = vin.length == 17 && vin.all { it.isLetterOrDigit() }
        Log.v(TAG, "Validating VIN '$vin': $valid")
        return valid
    }

    /**
     * Utility to extract raw data bytes from an OBD response string.
     */
    fun extractDataBytes(response: String): List<Int> {
        if (response.isBlank() || response.contains("NO DATA", ignoreCase = true)) return emptyList()

        // Handle multi-line responses by stripping headers like "0:", "1:"
        val lines = response.lines().map { it.trim() }
        val allHexTokens = mutableListOf<String>()
        
        for (line in lines) {
            val content = if (line.contains(":") && line.indexOf(":") < 3) {
                line.substring(line.indexOf(":") + 1).trim()
            } else {
                line
            }
            
            val tokens = if (content.contains(" ")) {
                content.split(" ").filter { it.isNotBlank() }
            } else {
                content.chunked(2).filter { it.length == 2 }
            }
            allHexTokens.addAll(tokens)
        }

        if (allHexTokens.size <= 2) return emptyList()

        // Drop the echo and mode bytes (e.g., "41 0C")
        return allHexTokens.drop(2).mapNotNull {
            runCatching { it.toInt(16) }.getOrNull()
        }
    }
}
