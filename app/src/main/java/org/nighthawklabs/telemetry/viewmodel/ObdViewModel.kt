package org.nighthawklabs.telemetry.viewmodel

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.nighthawklabs.telemetry.data.repository.ITelemetryRepository
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.domain.SyncState
import org.nighthawklabs.telemetry.obd.ObdCommandExecutor
import org.nighthawklabs.telemetry.obd.ObdConnectionManager
import org.nighthawklabs.telemetry.obd.ObdDataSourceFactory
import org.nighthawklabs.telemetry.obd.ObdResponseParser
import org.nighthawklabs.telemetry.obd.SimulatedObdDataSource
import org.nighthawklabs.telemetry.obd.ev.SimulatedEvDataSource
import org.nighthawklabs.telemetry.service.ObdPollingService
import org.nighthawklabs.telemetry.sync.SyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ObdViewModel(
    private val pollingService: ObdPollingService,
    private val repository: ITelemetryRepository,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ViewModel() {

    private val dataSourceFactory = ObdDataSourceFactory(
        connectionManager,
        commandExecutor,
        responseParser
    )

    val connectionState: StateFlow<ConnectionState> = pollingService.connectionState

    val latestTelemetry: StateFlow<TelemetryRecord?> = repository.observeLatestTelemetry()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val pendingRecordCount: StateFlow<Int> = repository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val syncState: StateFlow<SyncState> = SyncManager.syncState

    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            val dataSource = dataSourceFactory.createDataSource(device)
            pollingService.connectAndStart(dataSource)
        }
    }

    fun connectWithIceSimulator(includeAggressivePhase: Boolean = false) {
        val dataSource = SimulatedObdDataSource(includeAggressivePhase)
        pollingService.connectAndStart(dataSource)
    }

    fun connectWithEvSimulator() {
        val dataSource = SimulatedEvDataSource()
        pollingService.connectAndStart(dataSource)
    }

    fun syncNow(context: Context) {
        viewModelScope.launch {
            SyncManager.triggerSync(context)
        }
    }
}
