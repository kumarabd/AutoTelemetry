package org.nighthawklabs.telemetry.obd

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset

private const val TAG = "ObdCommandExecutor"

class ObdCommandExecutor(
    private val connectionManager: ObdConnectionManager
) {

    private val charset: Charset = Charsets.US_ASCII

    private val inputStream: InputStream?
        get() = connectionManager.getInputStream()

    private val outputStream: OutputStream?
        get() = connectionManager.getOutputStream()

    suspend fun initializeObd(): Boolean {
        val initCommands = listOf(
            "ATZ",   // reset
            "ATE0",  // echo off
            "ATL0",  // linefeeds off
            "ATS0",  // spaces off
            "ATH0",  // headers off
            "ATSP0"  // auto protocol
        )

        Log.i(TAG, "Starting OBD initialization sequence...")
        for (cmd in initCommands) {
            val response = sendCommand(cmd)
            Log.d(TAG, "Command: $cmd -> Response: $response")
            
            if (!response.contains("OK", ignoreCase = true) &&
                !response.contains("SEARCHING", ignoreCase = true) &&
                !response.contains("ELM327", ignoreCase = true) // ATZ response
            ) {
                Log.w(TAG, "Unexpected response for $cmd: $response")
            }
        }
        Log.i(TAG, "OBD initialization sequence finished.")

        return true
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 2000L): String =
        withContext(Dispatchers.IO) {
            val out = outputStream
            val input = inputStream

            if (out == null || input == null) {
                Log.e(TAG, "Cannot send command '$command', streams are null (disconnected?)")
                return@withContext ""
            }

            try {
                // Clear any existing data
                val clearedBytes = if (input.available() > 0) {
                    val count = input.available()
                    input.skip(count.toLong())
                    count
                } else 0
                
                if (clearedBytes > 0) {
                    Log.v(TAG, "Cleared $clearedBytes bytes from input buffer before sending '$command'")
                }

                val cmdWithNewline = "$command\r"
                Log.v(TAG, ">>> Sending command: '$command'")
                out.write(cmdWithNewline.toByteArray(charset))
                out.flush()

                val response = readResponse(input, timeoutMs)
                Log.v(TAG, "<<< Received response for '$command': '$response'")
                response
            } catch (e: IOException) {
                Log.e(TAG, "IOException while sending command '$command': ${e.message}", e)
                ""
            }
        }

    private fun readResponse(input: InputStream, timeoutMs: Long): String {
        val reader = BufferedReader(InputStreamReader(input, charset))
        val startTime = System.currentTimeMillis()
        val sb = StringBuilder()

        try {
            while (true) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    Log.w(TAG, "Timeout ($timeoutMs ms) waiting for OBD response. Partial data: '${sb.toString().trim()}'")
                    break
                }

                // Bluetooth sockets can be slow, we check availability or ready state
                if (reader.ready() || input.available() > 0) {
                    val char = reader.read()
                    if (char == -1) break
                    val c = char.toChar()
                    
                    if (c == '>') {
                        Log.v(TAG, "Prompt character '>' received.")
                        break
                    }
                    sb.append(c)
                } else {
                    // Small sleep to avoid tight loop while waiting for characters
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading OBD response: ${e.message}", e)
        }

        val raw = sb.toString().trim()
        return raw
    }
}
