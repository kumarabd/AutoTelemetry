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
        if (response.isBlank() || response.contains("NO DATA", ignoreCase = true)) return null
        
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
            
            // Look for "4902" (Mode 09 PID 02 response)
            val searchPattern = "4902"
            val index = hexString.indexOf(searchPattern)
            if (index == -1) {
                // Try to find if it just returned the raw VIN bytes without the mode/pid echo
                // Some adapters might behave differently or we might be looking at raw data
                if (hexString.length >= 34) { // 17 chars * 2 hex digits
                    // Heuristic: check if it looks like ASCII VIN (starts with letter/number)
                    val candidate = hexString.take(34)
                    val vin = hexToAscii(candidate)
                    if (isValidVin(vin)) return vin
                }
                return null
            }

            // The format is usually: 49 02 01 <vin_bytes> 
            // where 01 is the number of data items. 
            // We skip 49 (1 byte), 02 (1 byte), and typically one more byte for data count.
            val dataStart = index + 6 
            if (hexString.length < dataStart + 34) return null
            
            val vinHex = hexString.substring(dataStart, dataStart + 34)
            val vin = hexToAscii(vinHex)
            
            if (isValidVin(vin)) vin else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VIN from: $response", e)
            null
        }
    }

    private fun hexToAscii(hex: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val str = hex.substring(i, i + 2)
            output.append(str.toInt(16).toChar())
            i += 2
        }
        return output.toString().trim()
    }

    private fun isValidVin(vin: String): Boolean {
        // VIN is exactly 17 characters, alphanumeric, no I, O, Q
        return vin.length == 17 && vin.all { it.isLetterOrDigit() }
    }

    /**
     * Utility to extract raw data bytes from an OBD response string.
     */
    fun extractDataBytes(response: String): List<Int> {
        if (response.isBlank() || response.contains("NO DATA", ignoreCase = true)) return emptyList()

        val cleaned = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .trim()

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
        // Usually the first two bytes are the response mode (e.g., 01 -> 41) and PID
        return allHexTokens.drop(2).mapNotNull {
            runCatching { it.toInt(16) }.getOrNull()
        }
    }
}
