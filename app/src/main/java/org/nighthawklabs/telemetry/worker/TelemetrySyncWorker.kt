package org.nighthawklabs.telemetry.worker

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.nighthawklabs.telemetry.data.local.AppDatabase
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.firebase.FirebaseSessionManager
import org.nighthawklabs.telemetry.firebase.FirebaseTelemetryRemoteDataSource

private const val TAG = "TelemetrySyncWorker"

class TelemetrySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting telemetry sync work. id=$id runAttemptCount=$runAttemptCount")

        // Build dependencies locally for now. This can be refactored to DI later.
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "telemetry.db"
        ).build()

        val dao = db.telemetryDao()
        val repository = TelemetryRepository(dao)
        val sessionManager = FirebaseSessionManager()
        val remoteDataSource = FirebaseTelemetryRemoteDataSource(
            firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
            sessionManager = sessionManager
        )

        val batch = try {
            repository.getPendingBatch(limit = 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending batch", e)
            return Result.failure()
        }

        if (batch == null) {
            Log.d(TAG, "No pending telemetry records to sync")
            return Result.success()
        }

        Log.d(TAG, "Loaded batch ${batch.batchId} with ${batch.records.size} records")

        return try {
            repository.markBatchSyncing(batch)
            Log.d(TAG, "Marked batch ${batch.batchId} as SYNCING")

            val success = remoteDataSource.uploadBatch(batch)

            if (success) {
                repository.markBatchSynced(batch)
                Log.d(TAG, "Telemetry batch ${batch.batchId} synced successfully")
                Result.success()
            } else {
                repository.markBatchFailed(batch)
                Log.w(TAG, "Upload reported failure for batch ${batch.batchId}, scheduling retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during telemetry sync for batch ${batch.batchId}", e)

            val isTransient = e is FirebaseNetworkException ||
                (e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.UNAVAILABLE)

            try {
                repository.markBatchFailed(batch)
                Log.w(TAG, "Marked batch ${batch.batchId} as FAILED due to exception")
            } catch (inner: Exception) {
                Log.e(TAG, "Failed to mark batch ${batch.batchId} as FAILED after exception", inner)
            }

            if (isTransient) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

