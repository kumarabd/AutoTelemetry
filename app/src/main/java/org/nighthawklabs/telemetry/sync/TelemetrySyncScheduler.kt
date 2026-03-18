package org.nighthawklabs.telemetry.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import org.nighthawklabs.telemetry.worker.TelemetrySyncWorker
import java.util.concurrent.TimeUnit

private const val UNIQUE_PERIODIC_WORK_NAME = "telemetry_periodic_sync"
private const val UNIQUE_ONE_TIME_WORK_NAME = "telemetry_one_time_sync"
private const val TAG = "TelemetrySyncScheduler"

object TelemetrySyncScheduler {

    fun enqueueOneTimeSync(context: Context) {
        Log.i(TAG, "Enqueuing OneTimeSync. (Constraints: NONE for manual trigger)")
        
        val workManager = WorkManager.getInstance(context)
        
        // Removing network constraints for the manual trigger to ensure it starts 
        // and we can see logs even if the system thinks there's no internet.
        val workRequest = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Log.d(TAG, "OneTimeSync work request enqueued with REPLACE policy.")
    }

    fun enqueuePeriodicSync(context: Context) {
        Log.i(TAG, "Enqueuing PeriodicSync (15min interval)")
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
