package org.nighthawklabs.telemetry.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.nighthawklabs.telemetry.analytics.TripAnalyticsEngine
import org.nighthawklabs.telemetry.data.repository.DrivingTripRepository
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.domain.TripSummary

class TripHistoryViewModel(
    private val tripRepository: DrivingTripRepository,
    private val telemetryRepository: TelemetryRepository,
    private val analyticsEngine: TripAnalyticsEngine = TripAnalyticsEngine()
) : ViewModel() {

    private val _tripSummaries = MutableStateFlow<List<TripSummary>>(emptyList())
    val tripSummaries: StateFlow<List<TripSummary>> = _tripSummaries

    fun loadTrips() {
        viewModelScope.launch {
            val trips = tripRepository.getTripHistory()
            val summaries = mutableListOf<TripSummary>()
            for (trip in trips) {
                val telemetry = telemetryRepository.getTelemetryForTrip(trip.tripId)
                val summary = analyticsEngine.computeSummary(trip.tripId, telemetry)
                if (summary != null) {
                    summaries += summary
                }
            }
            _tripSummaries.value = summaries
        }
    }
}

