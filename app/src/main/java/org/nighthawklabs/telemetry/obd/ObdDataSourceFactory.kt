package org.nighthawklabs.telemetry.obd

import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nighthawklabs.telemetry.obd.ev.EvObdDataSource
import org.nighthawklabs.telemetry.obd.ice.IceObdDataSource
import java.net.HttpURLConnection
import java.net.URL
import java.util.Scanner

private const val TAG = "ObdDataSourceFactory"

class ObdDataSourceFactory(
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) {

    /**
     * Detects vehicle type by reading the VIN and decoding it using the NHTSA vPIC API.
     * Defaults to EV if detection fails.
     */
    suspend fun createDataSource(device: BluetoothDevice): ObdDataSource {
        connectionManager.connect(device)
        return try {
            commandExecutor.initializeObd()
            
            val vinRaw = commandExecutor.sendCommand("0902")
            val vin = responseParser.parseVin(vinRaw) ?: ""
            Log.d(TAG, "Parsed VIN from vehicle: '$vin'")

            val isEv = if (vin.isNotBlank()) {
                decodeVehicleTypeFromVin(vin)
            } else {
                Log.w(TAG, "Could not retrieve VIN, falling back to EV.")
                true
            }
            
            if (isEv) {
                Log.d(TAG, "Using EV Data Source")
                EvObdDataSource(device, connectionManager, commandExecutor, responseParser)
            } else {
                Log.d(TAG, "Using ICE Data Source")
                IceObdDataSource(device, connectionManager, commandExecutor, responseParser)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection process failed, defaulting to EV", e)
            EvObdDataSource(device, connectionManager, commandExecutor, responseParser)
        } finally {
            connectionManager.disconnect()
        }
    }

    private suspend fun decodeVehicleTypeFromVin(vin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // NHTSA vPIC API: Decode VIN Values Extended
            val url = URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValuesExtended/$vin?format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.use { it.bufferedReader().readText() }
                Log.d(TAG, "NHTSA API Response: $responseBody")
                
                // Simple check for "FuelTypePrimary" or "FuelTypeSecondary"
                // Standard values include "Electric", "Gasoline", "Diesel", "Flexible Fuel Vehicle (FFV)"
                // Note: Using string search to avoid adding a JSON library dependency if not strictly needed
                val isElectric = responseBody.contains("\"FuelTypePrimary\":\"Electric\"", ignoreCase = true) ||
                        responseBody.contains("\"FuelTypeSecondary\":\"Electric\"", ignoreCase = true) ||
                        responseBody.contains("\"ElectrificationLevel\":\"Battery Electric Vehicle (BEV)\"", ignoreCase = true)
                
                Log.i(TAG, "VIN decoding complete. Is Electric: $isElectric")
                isElectric
            } else {
                Log.w(TAG, "NHTSA API returned error code: ${conn.responseCode}. Falling back to EV.")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding VIN via NHTSA API", e)
            true // Fallback to EV as requested
        }
    }
}
