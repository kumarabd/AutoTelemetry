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

        for (cmd in initCommands) {
            val response = sendCommand(cmd)
            if (!response.contains("OK", ignoreCase = true) &&
                !response.contains("SEARCHING", ignoreCase = true)
            ) {
                Log.w(TAG, "Unexpected init response for $cmd: $response")
            }
        }

        return true
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 2000L): String =
        withContext(Dispatchers.IO) {
            val out = outputStream
            val input = inputStream

            if (out == null || input == null) {
                Log.e(TAG, "Cannot send command, streams are null")
                return@withContext ""
            }

            try {
                // Clear any existing data
                while (input.available() > 0) {
                    input.read()
                }

                val cmdWithNewline = "$command\r"
                Log.d(TAG, "Sending OBD command: $command")
                out.write(cmdWithNewline.toByteArray(charset))
                out.flush()

                readResponse(input, timeoutMs)
            } catch (e: IOException) {
                Log.e(TAG, "Error sending command $command", e)
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
                    Log.w(TAG, "Timeout waiting for OBD response")
                    break
                }

                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line.contains(">")) {
                        sb.append(line.replace(">", ""))
                        break
                    }
                    sb.append(line).append(' ')
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading OBD response", e)
        }

        val raw = sb.toString().trim()
        Log.d(TAG, "Raw OBD response: $raw")
        return raw
    }
}

