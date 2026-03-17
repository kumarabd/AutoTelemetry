package org.nighthawklabs.telemetry.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nighthawklabs.telemetry.domain.SyncState

object SyncManager {
    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun triggerSync(context: Context) {
        _syncState.value = SyncState.SYNCING
        
        // Trigger the one-time sync using the scheduler
        TelemetrySyncScheduler.enqueueOneTimeSync(context)
        
        // Optimistically set to SUCCESS for UI feedback as per docs
        // In a real app, you might observe WorkManager WorkInfo for the actual result
        _syncState.value = SyncState.SUCCESS
    }
}
