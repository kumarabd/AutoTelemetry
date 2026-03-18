package org.nighthawklabs.telemetry.firebase

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import org.nighthawklabs.telemetry.domain.TelemetryBatch
import org.nighthawklabs.telemetry.domain.TelemetryRecord

private const val TAG = "FirebaseTelemetryRemote"

class FirebaseTelemetryRemoteDataSource(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val sessionManager: FirebaseSessionManager
) {

    suspend fun uploadBatch(batch: TelemetryBatch): Boolean {
        Log.i(TAG, "Starting batch upload for ${batch.records.size} records...")
        
        val user = try {
            sessionManager.ensureSignedIn()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during authentication process: ${e.message}", e)
            null
        }
        
        if (user == null) {
            Log.e(TAG, "Cannot upload batch: authentication failed. Check if Anonymous Auth is enabled in Firebase Console.")
            return false
        }

        val uploaderUserId = user.uid
        val uploadedAt = System.currentTimeMillis()
        Log.d(TAG, "Authenticated as: $uploaderUserId. Preparing Firestore batch...")

        val writeBatch: WriteBatch = firestore.batch()

        for (record in batch.records) {
            val vehicleId = record.vehicleId ?: "unknown_vehicle"
            val docRef = firestore
                .collection("telemetry_vehicles")
                .document(vehicleId)
                .collection("obd")
                .document(record.id)

            val data = mapRecordToDocument(record, uploadedAt, uploaderUserId)
            writeBatch.set(docRef, data)
        }

        return try {
            Log.d(TAG, "Committing ${batch.records.size} operations in Firestore batch...")
            writeBatch.commit().await()
            Log.i(TAG, "Successfully committed batch to Firestore.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore commit failed for batch ${batch.batchId}: ${e.message}", e)
            false
        }
    }

    private fun mapRecordToDocument(
        record: TelemetryRecord,
        uploadedAt: Long,
        uploaderUserId: String
    ): Map<String, Any?> {
        return mapOf(
            "id" to record.id,
            "timestamp" to record.timestamp,
            "rpm" to record.rpm,
            "speed" to record.speed,
            "coolantTemp" to record.coolantTemp,
            "soc" to record.soc,
            "batteryTemp" to record.batteryTemp,
            "latitude" to record.latitude,
            "longitude" to record.longitude,
            "ignitionOn" to record.ignitionOn,
            "vehicleId" to (record.vehicleId ?: "unknown_vehicle"),
            "source" to record.source,
            "syncStatus" to "SYNCED",
            "createdAt" to record.createdAt,
            "updatedAt" to record.updatedAt,
            "uploadedAt" to uploadedAt,
            "uploaderUserId" to uploaderUserId
        )
    }
}
