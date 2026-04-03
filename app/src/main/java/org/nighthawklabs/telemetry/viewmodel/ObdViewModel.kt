package org.nighthawklabs.telemetry.viewmodel

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.nighthawklabs.telemetry.data.repository.ITelemetryRepository
import org.nighthawklabs.telemetry.data.repository.IVehicleRepository
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ObdViewModel(
    private val pollingService: ObdPollingService,
    private val telemetryRepository: ITelemetryRepository,
    private val vehicleRepository: IVehicleRepository,
    private val connectionManager: ObdConnectionManager,
    private val commandExecutor: ObdCommandExecutor,
    private val responseParser: ObdResponseParser
) : ViewModel() {

    private val dataSourceFactory = ObdDataSourceFactory(
        connectionManager,
        commandExecutor,
        responseParser,
        vehicleRepository
    )

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    val connectionState: StateFlow<ConnectionState> = pollingService.connectionState

    val latestTelemetry: StateFlow<TelemetryRecord?> = telemetryRepository.observeLatestTelemetry()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val pendingRecordCount: StateFlow<Int> = telemetryRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val syncState: StateFlow<SyncState> = SyncManager.syncState

    fun showMessage(text: String) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.Message(text))
        }
    }

    fun showError(text: String) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.Error(text))
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            try {
                val dataSource = dataSourceFactory.createDataSource(device)
                pollingService.connectAndStart(dataSource)
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.Error("Connection failed: ${e.message}"))
            }
        }
    }

    fun connectWithIceSimulator(includeAggressivePhase: Boolean = false) {
        viewModelScope.launch {
            try {
                val dataSource = SimulatedObdDataSource(includeAggressivePhase)
                vehicleRepository.saveVehicleMetadata(dataSource.vehicleId, dataSource.getMetadata())
                pollingService.connectAndStart(dataSource)
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.Error("Failed to start ICE simulation"))
            }
        }
    }

    fun connectWithEvSimulator() {
        viewModelScope.launch {
            try {
                val dataSource = SimulatedEvDataSource()
                vehicleRepository.saveVehicleMetadata(dataSource.vehicleId, dataSource.getMetadata())
                pollingService.connectAndStart(dataSource)
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.Error("Failed to start EV simulation"))
            }
        }
    }

    fun syncNow(context: Context) {
        viewModelScope.launch {
            SyncManager.triggerSync(context)
        }
    }

    sealed class UiEvent {
        data class Message(val text: String) : UiEvent()
        data class Error(val text: String) : UiEvent()
    }
}
