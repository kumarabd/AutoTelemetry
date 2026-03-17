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
        val user = sessionManager.ensureSignedIn()
        if (user == null) {
            Log.e(TAG, "Cannot upload batch: no authenticated user")
            return false
        }

        val uploaderUserId = user.uid
        val uploadedAt = System.currentTimeMillis()

        val writeBatch: WriteBatch = firestore.batch()

        for (record in batch.records) {
            val vehicleId = record.vehicleId ?: "unknown_vehicle"
            val docRef = firestore
                .collection("telemetry_vehicles")
                .document(vehicleId)
                .collection("records")
                .document(record.id)

            val data = mapRecordToDocument(record, uploadedAt, uploaderUserId)
            writeBatch.set(docRef, data)
        }

        return try {
            writeBatch.commit().await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload batch ${batch.batchId}", e)
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
            "latitude" to record.latitude,
            "longitude" to record.longitude,
            "ignitionOn" to record.ignitionOn,
            "vehicleId" to (record.vehicleId ?: "unknown_vehicle"),
            "source" to record.source,
            "syncStatus" to record.syncStatus.name,
            "createdAt" to record.createdAt,
            "updatedAt" to record.updatedAt,
            "uploadedAt" to uploadedAt,
            "uploaderUserId" to uploaderUserId
        )
    }
}

