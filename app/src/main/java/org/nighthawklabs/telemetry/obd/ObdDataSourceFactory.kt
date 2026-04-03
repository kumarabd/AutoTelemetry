package org.nighthawklabs.telemetry.obd

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.github.eltonvs.obd.command.at.DescribeProtocolCommand
import com.github.eltonvs.obd.command.at.DescribeProtocolNumberCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nighthawklabs.telemetry.data.repository.IVehicleRepository
import org.nighthawklabs.telemetry.model.VehicleMetadata
import org.nighthawklabs.telemetry.obd.ev.EvObdDataSource
import org.nighthawklabs.telemetry.obd.ice.IceObdDataSource
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ObdDataSourceFactory"

class ObdDataSourceFactory(
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser,
    private val vehicleRepository: IVehicleRepository
) {

    /**
     * Detects vehicle type by reading the VIN and decoding it using the NHTSA vPIC API.
     * Throws exception if initial connection fails.
     */
    suspend fun createDataSource(device: BluetoothDevice): ObdDataSource {
        Log.d(TAG, "Creating data source for device: ${device.name} (${device.address})")
        val connected = connectionManager.connect(device)
        if (!connected) {
            throw Exception("Could not establish Bluetooth connection to ${device.name ?: device.address}")
        }

        return try {
            Log.d(TAG, "Initializing OBD protocols via library...")
            commandExecutor.initializeObd()
            
            // Warm up / Sync
            Log.d(TAG, "Warming up connection (0100 - supported PIDs)...")
            val pid0100Raw = commandExecutor.sendRawCommand("0100")
            Log.i(TAG, "0100 raw response: '$pid0100Raw'")

            // Debug: Check which protocol was actually found
            val protocolDesc = commandExecutor.executeCommand(DescribeProtocolCommand()) as? String
            val protocolNum = commandExecutor.executeCommand(DescribeProtocolNumberCommand()) as? String
            Log.i(TAG, "Current Protocol: $protocolDesc (Number: $protocolNum)")

            Log.d(TAG, "Requesting VIN (0902)...")
            val vinRaw = commandExecutor.sendRawCommand("0902")
            Log.i(TAG, "0902 raw response: '$vinRaw'")
            val vin = responseParser.parseVin(vinRaw) ?: ""
            Log.i(TAG, "Final parsed VIN: '$vin'")

            val metadata = if (vin.isNotBlank()) {
                decodeVehicleMetadataFromVin(vin)
            } else {
                Log.w(TAG, "VIN is empty. Falling back to default EV metadata.")
                VehicleMetadata(vin = "", isEv = true)
            }
            
            val vehicleId = if (vin.isNotBlank()) vin else device.address
            vehicleRepository.saveVehicleMetadata(vehicleId, metadata)
            
            if (metadata.isEv) {
                Log.i(TAG, "Classification: EV. Source: ${metadata.make ?: "Generic"} ${metadata.model ?: "EV"}")
                EvObdDataSource(device, connectionManager, commandExecutor, responseParser)
            } else {
                Log.i(TAG, "Classification: ICE. Source: ${metadata.make ?: "Generic"} ${metadata.model ?: "ICE"}")
                IceObdDataSource(device, connectionManager, commandExecutor, responseParser)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed. Defaulting to EV.", e)
            EvObdDataSource(device, connectionManager, commandExecutor, responseParser)
        } finally {
            connectionManager.disconnect()
        }
    }

    private suspend fun decodeVehicleMetadataFromVin(vin: String): VehicleMetadata = withContext(Dispatchers.IO) {
        try {
            val urlString = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValuesExtended/$vin?format=json"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.use { it.bufferedReader().readText() }
                
                fun extractValue(key: String): String? {
                    val pattern = "\"$key\":\"([^\"]*)\"".toRegex()
                    return pattern.find(responseBody)?.groupValues?.get(1)
                }

                val make = extractValue("Make")
                val model = extractValue("Model")
                val yearStr = extractValue("ModelYear")
                val fuelType = extractValue("FuelTypePrimary")
                val electrification = extractValue("ElectrificationLevel")

                val isElectric = fuelType?.contains("Electric", ignoreCase = true) == true ||
                        electrification?.contains("Battery Electric Vehicle", ignoreCase = true) == true
                
                Log.i(TAG, "Decoded: $make $model ($yearStr). IsEv: $isElectric")
                
                VehicleMetadata(
                    vin = vin,
                    make = make,
                    model = model,
                    year = yearStr?.toIntOrNull(),
                    fuelType = fuelType,
                    electrificationLevel = electrification,
                    isEv = isElectric
                )
            } else {
                VehicleMetadata(vin = vin, isEv = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "NHTSA Error", e)
            VehicleMetadata(vin = vin, isEv = true)
        }
    }
}
