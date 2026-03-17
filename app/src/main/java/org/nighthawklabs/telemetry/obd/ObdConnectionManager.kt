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
            val tmpSocket = device.createRfcommSocketToServiceRecord(sppUuid)
            bluetoothAdapter.cancelDiscovery()

            Log.d(TAG, "Connecting to device: ${device.name} - ${device.address}")
            tmpSocket.connect()

            socket = tmpSocket
            inputStream = tmpSocket.inputStream
            outputStream = tmpSocket.outputStream

            Log.d(TAG, "Bluetooth connected")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error connecting to device", e)
            disconnect()
            false
        }
    }

    fun getInputStream(): InputStream? = inputStream

    fun getOutputStream(): OutputStream? = outputStream

    fun isConnected(): Boolean {
        return socket?.isConnected == true
    }

    fun disconnect() {
        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream", e)
        } finally {
            inputStream = null
        }

        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream", e)
        } finally {
            outputStream = null
        }

        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket", e)
        } finally {
            socket = null
        }
    }
}

