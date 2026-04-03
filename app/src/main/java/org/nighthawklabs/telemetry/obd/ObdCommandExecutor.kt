package org.nighthawklabs.telemetry.obd

import android.util.Log
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import com.github.eltonvs.obd.command.AdaptiveTimingMode
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdProtocols
import com.github.eltonvs.obd.command.NoDataException
import com.github.eltonvs.obd.command.ObdResponse
import com.github.eltonvs.obd.command.Switcher
import com.github.eltonvs.obd.command.at.ResetAdapterCommand
import com.github.eltonvs.obd.command.at.SelectProtocolCommand
import com.github.eltonvs.obd.command.at.SetAdaptiveTimingCommand
import com.github.eltonvs.obd.command.at.SetEchoCommand
import com.github.eltonvs.obd.command.at.SetHeadersCommand
import com.github.eltonvs.obd.command.at.SetLineFeedCommand
import com.github.eltonvs.obd.command.at.SetSpacesCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "ObdCommandExecutor"

class ObdCommandExecutor(
    private val connectionManager: ObdConnectionManager
) {

    private var obdConnection: ObdDeviceConnection? = null
    private var cachedInputStream: InputStream? = null
    private var cachedOutputStream: OutputStream? = null

    private val inputStream: InputStream?
        get() = connectionManager.getInputStream()

    private val outputStream: OutputStream?
        get() = connectionManager.getOutputStream()

    private fun getOrBuildConnection(): ObdDeviceConnection? {
        val input = inputStream
        val output = outputStream
        if (input == null || output == null) {
            obdConnection = null
            cachedInputStream = null
            cachedOutputStream = null
            return null
        }
        // Rebuild connection if streams changed (e.g. after disconnect/reconnect)
        if (obdConnection == null || cachedInputStream != input || cachedOutputStream != output) {
            obdConnection = ObdDeviceConnection(input, output)
            cachedInputStream = input
            cachedOutputStream = output
        }
        return obdConnection
    }

    suspend fun initializeObd(): Boolean = withContext(Dispatchers.IO) {
        val connection = getOrBuildConnection() ?: return@withContext false

        Log.i(TAG, "Starting OBD initialization via kotlin-obd-api...")
        try {
            suspend fun logInit(cmd: ObdCommand) {
                val resp = connection.run(cmd)
                // rawResponse.value contains what the adapter returned (e.g. OK / 0: ... / etc.)
                Log.i(TAG, "Init ${cmd.tag} raw='${resp.rawResponse.value}' parsed='${resp.value}'")
            }

            // Standard initialization sequence
            logInit(ResetAdapterCommand())
            delay(500)
            logInit(SetAdaptiveTimingCommand(AdaptiveTimingMode.AUTO_1))
            logInit(SetEchoCommand(Switcher.OFF))
            logInit(SetLineFeedCommand(Switcher.OFF))
            logInit(SetSpacesCommand(Switcher.OFF))
            logInit(SetHeadersCommand(Switcher.OFF))
            logInit(SelectProtocolCommand(ObdProtocols.AUTO))
            delay(2000)
            logInit(SelectProtocolCommand(ObdProtocols.ISO_15765_4_CAN))
            delay(1000)

            Log.i(TAG, "OBD initialization sequence finished.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OBD: ${e.message}", e)
            false
        }
    }

    /**
     * Executes a high-level command from the library.
     */
    suspend fun executeCommand(command: ObdCommand): Any? = withContext(Dispatchers.IO) {
        val connection = getOrBuildConnection() ?: return@withContext null
        try {
            val response = connection.run(command)
            Log.i(TAG, "Command ${command.tag} raw='${response.rawResponse.value}' parsed='${response.value}'")
            response.value
        } catch (e: Exception) {
            Log.e(TAG, "Error executing ${command.tag}: ${e.message}", e)
            null
        }
    }

    /**
     * Fallback for raw commands not in the library.
     */
    suspend fun sendRawCommand(rawCommand: String): String = withContext(Dispatchers.IO) {
        val connection = getOrBuildConnection() ?: return@withContext ""
        try {
            val command = object : ObdCommand() {
                override val tag: String = "Raw_$rawCommand"
                override val name: String = "Raw $rawCommand"
                override val mode: String = rawCommand.take(2)
                override val pid: String = if (rawCommand.length > 2) rawCommand.substring(2) else ""
                override val skipDigitCheck: Boolean = true
                override fun format(response: ObdResponse): String = response.value
            }
            val response = connection.run(command)
            Log.i(TAG, "Raw $rawCommand -> '${response.rawResponse.value}'")
            response.value
        } catch (e: NoDataException) {
            Log.v(TAG, "Raw $rawCommand: NO DATA (vehicle may not support this PID)")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error sending raw command '$rawCommand': ${e.message}", e)
            ""
        }
    }
}
