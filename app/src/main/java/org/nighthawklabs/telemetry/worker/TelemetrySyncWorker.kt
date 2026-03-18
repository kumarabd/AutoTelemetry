package org.nighthawklabs.telemetry.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.nighthawklabs.telemetry.ObdTelemetryApplication
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.domain.TelemetryBatch
import org.nighthawklabs.telemetry.firebase.FirebaseSessionManager
import org.nighthawklabs.telemetry.firebase.FirebaseTelemetryRemoteDataSource

private const val TAG = "TelemetrySyncWorker"

class TelemetrySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "--- Sync Worker Execution Started --- id=$id")

        val app = applicationContext as? ObdTelemetryApplication
        if (app == null) {
            Log.e(TAG, "Sync Error: Could not get application instance.")
            return Result.failure()
        }

        val db = app.database
        val dao = db.telemetryDao()
        val repository = TelemetryRepository(dao)
        val sessionManager = FirebaseSessionManager()
        val remoteDataSource = FirebaseTelemetryRemoteDataSource(
            firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
            sessionManager = sessionManager
        )

        var lastProcessedBatch: TelemetryBatch? = null

        try {
            var totalSyncedCount = 0
            while (true) {
                // Fetch a batch of records that are either PENDING or FAILED
                val batch = repository.getPendingBatch(limit = 100)
                
                if (batch == null || batch.records.isEmpty()) {
                    Log.i(TAG, "Sync completed successfully. Total records processed: $totalSyncedCount")
                    break
                }

                lastProcessedBatch = batch
                Log.d(TAG, "Syncing batch ${batch.batchId} with ${batch.records.size} records...")
                
                // Mark records as SYNCING before starting network operation
                repository.markBatchSyncing(batch)

                val uploadSuccessful = remoteDataSource.uploadBatch(batch)

                if (uploadSuccessful) {
                    repository.markBatchSynced(batch)
                    totalSyncedCount += batch.records.size
                    Log.d(TAG, "Successfully synced batch ${batch.batchId}")
                    lastProcessedBatch = null // Successfully handled
                } else {
                    repository.markBatchFailed(batch)
                    Log.w(TAG, "Batch upload failed: ${batch.batchId}. Rescheduling for later.")
                    return Result.retry()
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Critical sync exception", e)
            
            // If we have a batch that was in progress, revert it to FAILED so it's not "stuck"
            lastProcessedBatch?.let {
                Log.w(TAG, "Reverting batch ${it.batchId} to FAILED due to exception.")
                repository.markBatchFailed(it)
            }

            val isNetworkIssue = e is FirebaseNetworkException ||
                (e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.UNAVAILABLE)

            if (isNetworkIssue) {
                Log.i(TAG, "Network-related failure, retrying sync.")
                return Result.retry()
            } else {
                Log.e(TAG, "Non-recoverable error, failing sync task.")
                return Result.failure()
            }
        }
    }
}
