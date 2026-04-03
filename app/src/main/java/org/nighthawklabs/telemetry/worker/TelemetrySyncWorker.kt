package org.nighthawklabs.telemetry.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.nighthawklabs.telemetry.ObdTelemetryApplication
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.data.repository.VehicleRepository
import org.nighthawklabs.telemetry.domain.TelemetryBatch
import org.nighthawklabs.telemetry.firebase.FirebaseTelemetryRemoteDataSource

private const val TAG = "TelemetrySyncWorker"

class TelemetrySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ERROR_MESSAGE = "error_message"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "--- Sync Worker Execution Started --- id=$id")

        val app = applicationContext as? ObdTelemetryApplication
        if (app == null) {
            Log.e(TAG, "Sync Error: Could not get application instance.")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Internal application error"))
        }

        val db = app.database
        val telemetryRepository = TelemetryRepository(db.telemetryDao())
        val vehicleRepository = VehicleRepository(db.vehicleDao())
        
        val remoteDataSource = FirebaseTelemetryRemoteDataSource(
            firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                com.google.firebase.FirebaseApp.getInstance(),
                "ai-studio-da034855-9707-4368-9077-13ac833e4857"
            ),
            vehicleRepository = vehicleRepository
        )

        var lastProcessedBatch: TelemetryBatch? = null

        try {
            var totalSyncedCount = 0
            while (true) {
                val batch = telemetryRepository.getPendingBatch(limit = 100)
                
                if (batch == null || batch.records.isEmpty()) {
                    Log.i(TAG, "Sync completed successfully. Total records processed: $totalSyncedCount")
                    break
                }

                lastProcessedBatch = batch
                Log.d(TAG, "Syncing batch ${batch.batchId} with ${batch.records.size} records...")
                
                telemetryRepository.markBatchSyncing(batch)

                remoteDataSource.uploadBatch(batch)

                telemetryRepository.markBatchSynced(batch)
                totalSyncedCount += batch.records.size
                Log.d(TAG, "Successfully synced batch ${batch.batchId}")
                lastProcessedBatch = null 
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Critical sync exception: ${e.message}", e)
            
            lastProcessedBatch?.let {
                Log.w(TAG, "Reverting batch ${it.batchId} to FAILED due to exception.")
                telemetryRepository.markBatchFailed(it)
            }

            val isNetworkIssue = e is FirebaseNetworkException ||
                (e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.UNAVAILABLE)

            val isPermissionDenied = e is FirebaseFirestoreException && 
                e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

            val errorMessage = e.message ?: "Unknown error"

            return when {
                isNetworkIssue -> {
                    Log.i(TAG, "Network-related failure, retrying sync via WorkManager.")
                    Result.retry()
                }
                isPermissionDenied -> {
                    Log.e(TAG, "Permission denied. Failing task to notify user.")
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Permission Denied: $errorMessage"))
                }
                else -> {
                    Log.e(TAG, "Non-recoverable error, failing sync task.")
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
                }
            }
        }
    }
}
