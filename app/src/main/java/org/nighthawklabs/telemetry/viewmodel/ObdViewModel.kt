package org.nighthawklabs.telemetry.viewmodel

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.nighthawklabs.telemetry.data.repository.ITelemetryRepository
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.RealObdDataSource
import org.nighthawklabs.telemetry.obd.ObdResponseParser
import org.nighthawklabs.telemetry.obd.SimulatedObdDataSource
import org.nighthawklabs.telemetry.service.ObdPollingService
import org.nighthawklabs.telemetry.sync.TelemetrySyncScheduler

class ObdViewModel(
    private val pollingService: ObdPollingService,
    private val repository: ITelemetryRepository,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> =
        pollingService.connectionState

    val latestTelemetry: StateFlow<TelemetryRecord?> =
        repository.observeLatestTelemetry()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    val pendingRecordCount: StateFlow<Int> =
        repository.observePendingCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState

    fun connectToDevice(device: BluetoothDevice) {
        val dataSource = RealObdDataSource(
            device = device,
            connectionManager = connectionManager,
            commandExecutor = commandExecutor,
            responseParser = responseParser
        )
        pollingService.connectAndStart(dataSource)
    }

    fun connectWithSimulator(includeAggressivePhase: Boolean = false) {
        val dataSource = SimulatedObdDataSource(includeAggressivePhase = includeAggressivePhase)
        pollingService.connectAndStart(dataSource)
    }

    fun disconnect() {
        pollingService.stop()
    }

    fun syncNow(appContext: android.content.Context) {
        _syncState.value = SyncState.SYNCING
        TelemetrySyncScheduler.enqueueOneTimeSync(appContext)
        _syncState.value = SyncState.SUCCESS
    }
}
