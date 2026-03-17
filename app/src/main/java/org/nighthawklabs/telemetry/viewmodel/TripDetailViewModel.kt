package org.nighthawklabs.telemetry.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.nighthawklabs.telemetry.analytics.DrivingBehaviorAnalyzer
import org.nighthawklabs.telemetry.analytics.TripAnalyticsEngine
import org.nighthawklabs.telemetry.analytics.VehicleHealthAnalyzer
import org.nighthawklabs.telemetry.data.repository.DrivingTripRepository
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.domain.DrivingEvent
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.domain.TripSummary
import org.nighthawklabs.telemetry.domain.VehicleAlert

class TripDetailViewModel(
    private val tripId: String,
    private val tripRepository: DrivingTripRepository,
    private val telemetryRepository: TelemetryRepository,
    private val analyticsEngine: TripAnalyticsEngine = TripAnalyticsEngine(),
    private val behaviorAnalyzer: DrivingBehaviorAnalyzer = DrivingBehaviorAnalyzer(),
    private val healthAnalyzer: VehicleHealthAnalyzer = VehicleHealthAnalyzer()
) : ViewModel() {

    private val _summary = MutableStateFlow<TripSummary?>(null)
    val summary: StateFlow<TripSummary?> = _summary

    private val _telemetry = MutableStateFlow<List<TelemetryRecord>>(emptyList())
    val telemetry: StateFlow<List<TelemetryRecord>> = _telemetry

    private val _events = MutableStateFlow<List<DrivingEvent>>(emptyList())
    val events: StateFlow<List<DrivingEvent>> = _events

    private val _alerts = MutableStateFlow<List<VehicleAlert>>(emptyList())
    val alerts: StateFlow<List<VehicleAlert>> = _alerts

    fun load() {
        viewModelScope.launch {
            val trip = tripRepository.getTripById(tripId)
            if (trip == null) {
                _summary.value = null
                _telemetry.value = emptyList()
                _events.value = emptyList()
                _alerts.value = emptyList()
                return@launch
            }

            val telemetryRecords = telemetryRepository.getTelemetryForTrip(tripId)
            _telemetry.value = telemetryRecords

            _summary.value = analyticsEngine.computeSummary(tripId, telemetryRecords)
            _events.value = behaviorAnalyzer.analyze(tripId, telemetryRecords)
            _alerts.value = healthAnalyzer.analyze(telemetryRecords)
        }
    }
}
