package org.nighthawklabs.telemetry.sync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nighthawklabs.telemetry.domain.SyncState
import org.nighthawklabs.telemetry.worker.TelemetrySyncWorker

private const val TAG = "SyncManager"
private const val UNIQUE_ONE_TIME_WORK_NAME = "telemetry_one_time_sync"

object SyncManager {
    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var isObserving = false

    fun triggerSync(context: Context) {
        if (_syncState.value == SyncState.SYNCING) {
            Log.d(TAG, "Sync already in progress, skipping triggerSync request.")
            return
        }
        
        Log.i(TAG, "SyncManager: Triggering manual sync request...")
        
        _syncState.value = SyncState.SYNCING
        
        try {
            TelemetrySyncScheduler.enqueueOneTimeSync(context)
            Log.d(TAG, "Sync request successfully enqueued.")
            observeWorkStatus(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue sync request: ${e.message}", e)
            _syncState.value = SyncState.FAILED
        }
    }

    private fun observeWorkStatus(context: Context) {
        if (isObserving) return
        isObserving = true

        val workManager = WorkManager.getInstance(context)
        
        CoroutineScope(Dispatchers.Main).launch {
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_ONE_TIME_WORK_NAME).collect { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@collect
                
                when (workInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        _syncState.value = SyncState.SYNCING
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (_syncState.value == SyncState.SYNCING) {
                            Toast.makeText(context, "Sync completed successfully", Toast.LENGTH_SHORT).show()
                        }
                        _syncState.value = SyncState.SUCCESS
                        launch {
                            kotlinx.coroutines.delay(3000)
                            if (_syncState.value == SyncState.SUCCESS) _syncState.value = SyncState.IDLE
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(TelemetrySyncWorker.KEY_ERROR_MESSAGE) ?: "Check connection."
                        Toast.makeText(context, "Sync failed: $error", Toast.LENGTH_LONG).show()
                        _syncState.value = SyncState.FAILED
                        launch {
                            kotlinx.coroutines.delay(5000)
                            if (_syncState.value == SyncState.FAILED) _syncState.value = SyncState.IDLE
                        }
                    }
                    else -> {
                        _syncState.value = SyncState.IDLE
                    }
                }
            }
        }
    }
}
