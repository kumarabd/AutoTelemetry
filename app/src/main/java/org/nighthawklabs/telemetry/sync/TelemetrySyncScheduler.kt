package org.nighthawklabs.telemetry.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.nighthawklabs.telemetry.worker.TelemetrySyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

private const val UNIQUE_PERIODIC_WORK_NAME = "telemetry_periodic_sync"
private const val UNIQUE_ONE_TIME_WORK_NAME = "telemetry_one_time_sync"

object TelemetrySyncScheduler {

    private fun defaultConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

    fun enqueueOneTimeSync(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        val workManager = WorkManager.getInstance(context)
        
        scope.launch {
            try {
                val existing = workManager.getWorkInfosForUniqueWork(UNIQUE_ONE_TIME_WORK_NAME).await()
                val hasRunning = existing.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
                if (hasRunning) {
                    return@launch
                }

                val workRequest = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
                    .setConstraints(defaultConstraints())
                    .build()

                workManager.enqueueUniqueWork(
                    UNIQUE_ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
            } catch (e: Exception) {
                // Log or handle error
            }
        }
    }

    fun enqueuePeriodicSync(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(defaultConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
