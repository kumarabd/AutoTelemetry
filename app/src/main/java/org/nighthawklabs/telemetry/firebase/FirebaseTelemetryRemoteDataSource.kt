package org.nighthawklabs.telemetry.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import org.nighthawklabs.telemetry.data.repository.IVehicleRepository
import org.nighthawklabs.telemetry.domain.TelemetryBatch
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.obd.PidReading

private const val TAG = "FirebaseTelemetryRemote"

class FirebaseTelemetryRemoteDataSource(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val sessionManager: FirebaseSessionManager = FirebaseSessionManager(),
    private val vehicleRepository: IVehicleRepository
) {

    /**
     * Uploads a batch of telemetry records to Firestore.
     *
     * Firestore document structure:
     *
     *   telemetry_vehicles/{vehicleId}/raw/{recordId}
     *     ├── common fields  (speed, timestamp, gps, …)
     *     ├── vehicleType    ("EV" | "ICE")
     *     ├── readings       { "010C": { label, value, unit }, … }   ← all PIDs
     *     └── legacy fields  (rpm, coolantTemp / soc, batteryTemp)   ← kept for
     *                                                                    existing queries
     *
     * The [readings] map is stored as a Firestore map field so every PID can be
     * queried independently without flattening it into top-level fields.
     */
    suspend fun uploadBatch(batch: TelemetryBatch) {
        Log.i(TAG, "Uploading ${batch.records.size} records…")

        val currentUser = sessionManager.ensureSignedIn()
            ?: throw Exception("Authentication failed: user not signed in.")

        val uploaderUid = currentUser.uid
        val uploadedAt  = System.currentTimeMillis()

        val writeBatch: WriteBatch = firestore.batch()
        val vehicleIds = mutableSetOf<String>()

        for (record in batch.records) {
            val vehicleId = record.vehicleId ?: "unknown_vehicle"
            vehicleIds.add(vehicleId)

            val docRef = firestore
                .collection("telemetry_vehicles")
                .document(vehicleId)
                .collection("raw")
                .document(record.id)

            writeBatch.set(docRef, buildDocument(record, uploadedAt, uploaderUid))
        }

        // Update vehicle-level metadata document
        for (vId in vehicleIds) {
            val vehicleRef = firestore.collection("telemetry_vehicles").document(vId)
            val metadata   = vehicleRepository.getVehicleMetadata(vId)

            val vehicleDoc = mutableMapOf<String, Any?>(
                "lastSyncAt"      to uploadedAt,
                "lastUploaderUid" to uploaderUid,
                "ownerUid"        to uploaderUid
            )
            metadata?.let {
                vehicleDoc["vin"]                  = it.vin
                vehicleDoc["make"]                 = it.make
                vehicleDoc["model"]                = it.model
                vehicleDoc["year"]                 = it.year
                vehicleDoc["fuelType"]             = it.fuelType
                vehicleDoc["electrificationLevel"] = it.electrificationLevel
                vehicleDoc["isEv"]                 = it.isEv
            }

            writeBatch.set(vehicleRef, vehicleDoc, SetOptions.merge())
        }

        try {
            writeBatch.commit().await()
            Log.i(TAG, "Batch committed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Firestore commit failed: ${e.message}", e)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Document builder
    // -------------------------------------------------------------------------

    private fun buildDocument(
        record: TelemetryRecord,
        uploadedAt: Long,
        uploaderUid: String
    ): Map<String, Any?> {

        // Common fields present on every record
        val common = mapOf(
            "id"             to record.id,
            "vehicleType"    to record.vehicleType,
            "timestamp"      to record.timestamp,
            "speed"          to record.speed,
            "vehicleId"      to (record.vehicleId ?: "unknown_vehicle"),
            "tripId"         to record.tripId,
            "latitude"       to record.latitude,
            "longitude"      to record.longitude,
            "ignitionOn"     to record.ignitionOn,
            "source"         to record.source,
            "syncStatus"     to "SYNCED",
            "createdAt"      to record.createdAt,
            "updatedAt"      to record.updatedAt,
            "uploadedAt"     to uploadedAt,
            "uploaderUserId" to uploaderUid,
            "ownerUid"       to uploaderUid,
            // Full PID readings map — stored as a Firestore map so each PID is
            // individually queryable: readings.`010C`.value > 3000
            "readings"       to serialiseReadings(record.readings)
        )

        // Legacy top-level fields kept for backward-compat with existing Firestore queries
        val legacy: Map<String, Any?> = when (record) {
            is TelemetryRecord.Ice -> mapOf(
                "rpm"         to record.rpm,
                "coolantTemp" to record.coolantTemp
            )
            is TelemetryRecord.Ev -> mapOf(
                "soc"         to record.soc,
                "batteryTemp" to record.batteryTemp
            )
        }

        return common + legacy
    }

    /**
     * Converts Map<command, PidReading> to a plain Map Firestore can serialise.
     *
     * Firestore result shape:
     *   "readings": {
     *     "010C": { "label": "Engine RPM", "value": 2400.0, "unit": "rpm" },
     *     "010D": { "label": "Vehicle Speed", "value": 87.0,  "unit": "km/h" },
     *     …
     *   }
     *
     * The raw adapter string is intentionally omitted from the cloud document to
     * save storage; it is only useful for local debugging in Room.
     */
    private fun serialiseReadings(readings: Map<String, PidReading>): Map<String, Any?> =
        readings.mapValues { (_, reading) ->
            mapOf(
                "label" to reading.label,
                "value" to reading.value,
                "unit"  to reading.unit
            )
        }
}
