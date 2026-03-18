package org.nighthawklabs.telemetry.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val TAG = "ObdConnectionManager"

class ObdConnectionManager(context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        disconnect()

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
            return@withContext false
        }

        try {
            Log.d(TAG, "Creating RFCOMM socket for device: ${device.name} (${device.address})")
            val tmpSocket = device.createRfcommSocketToServiceRecord(sppUuid)
            
            if (bluetoothAdapter.isDiscovering) {
                Log.d(TAG, "Cancelling ongoing Bluetooth discovery...")
                bluetoothAdapter.cancelDiscovery()
            }

            Log.d(TAG, "Attempting to connect socket...")
            tmpSocket.connect()

            socket = tmpSocket
            inputStream = tmpSocket.inputStream
            outputStream = tmpSocket.outputStream

            Log.i(TAG, "Bluetooth connected successfully to ${device.name}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect to device ${device.name} (${device.address}): ${e.message}", e)
            disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connection: ${e.message}", e)
            disconnect()
            false
        }
    }

    fun getInputStream(): InputStream? = inputStream

    fun getOutputStream(): OutputStream? = outputStream

    fun isConnected(): Boolean {
        val connected = socket?.isConnected == true
        Log.v(TAG, "Checking connection state: $connected")
        return connected
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting and cleaning up resources...")
        try {
            inputStream?.close()
            Log.v(TAG, "Input stream closed")
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream: ${e.message}")
        } finally {
            inputStream = null
        }

        try {
            outputStream?.close()
            Log.v(TAG, "Output stream closed")
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream: ${e.message}")
        } finally {
            outputStream = null
        }

        try {
            socket?.close()
            Log.v(TAG, "Bluetooth socket closed")
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        } finally {
            socket = null
        }
        Log.i(TAG, "Disconnected.")
    }
}
