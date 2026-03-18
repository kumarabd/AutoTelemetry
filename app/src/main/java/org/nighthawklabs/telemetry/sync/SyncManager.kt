package org.nighthawklabs.telemetry.sync

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.nighthawklabs.telemetry.domain.SyncState

private const val TAG = "SyncManager"

object SyncManager {
    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    suspend fun triggerSync(context: Context) {
        if (_syncState.value == SyncState.SYNCING) {
            Log.d(TAG, "Sync already in progress, skipping triggerSync request.")
            return
        }
        
        Log.i(TAG, "SyncManager: Triggering manual sync request...")
        
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Requesting data sync...", Toast.LENGTH_SHORT).show()
        }

        _syncState.value = SyncState.SYNCING
        
        try {
            // Trigger the one-time sync using the scheduler
            TelemetrySyncScheduler.enqueueOneTimeSync(context)
            Log.d(TAG, "Sync request successfully enqueued.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue sync request: ${e.message}", e)
        } finally {
            // We set IDLE back to allow another manual request later
            // The actual progress can be observed via WorkManager if needed
            _syncState.value = SyncState.IDLE
        }
    }
}
