package org.nighthawklabs.telemetry.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.nighthawklabs.telemetry.ObdTelemetryApplication
import org.nighthawklabs.telemetry.R
import org.nighthawklabs.telemetry.data.repository.DrivingTripRepository
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.viewmodel.TripDetailViewModel

class TripDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
    }

    private val viewModel: TripDetailViewModel by viewModels {
        val tripId = intent.getStringExtra(EXTRA_TRIP_ID) ?: ""
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as ObdTelemetryApplication
                val db = app.database
                val tripRepo = DrivingTripRepository(db.drivingTripDao())
                val telemetryRepo = TelemetryRepository(db.telemetryDao())
                return TripDetailViewModel(
                    tripId = tripId,
                    tripRepository = tripRepo,
                    telemetryRepository = telemetryRepo
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_detail)

        val txtSummary: TextView = findViewById(R.id.txtTripSummary)
        val txtMetrics: TextView = findViewById(R.id.txtTripMetrics)
        val txtEvents: TextView = findViewById(R.id.txtDrivingEvents)
        val txtAlerts: TextView = findViewById(R.id.txtVehicleAlerts)
        val chart: LineChart = findViewById(R.id.chartSpeed)

        lifecycleScope.launch {
            viewModel.summary.collectLatest { summary ->
                if (summary == null) {
                    txtSummary.text = getString(R.string.trip_summary_missing)
                } else {
                    val durationMin = summary.durationMillis / 60000.0
                    txtSummary.text = String.format(
                        "Start: %tF %tT\nDuration: %.1f min\nDistance: %.1f km\nAvg: %.0f km/h\nMax: %.0f km/h\nRecords: %d",
                        summary.startTime,
                        summary.startTime,
                        durationMin,
                        summary.distanceKm,
                        summary.avgSpeedKmh,
                        summary.maxSpeedKmh,
                        summary.recordCount
                    )
                }
            }
        }

        lifecycleScope.launch {
            viewModel.telemetry.collectLatest { records ->
                if (records.isEmpty()) {
                    txtMetrics.text = getString(R.string.trip_metrics_missing)
                    chart.clear()
                    return@collectLatest
                }

                val idleMillis = viewModel.summary.value?.idleTimeMillis ?: 0L
                val harshEvents = viewModel.events.value.size
                val metricsText = String.format(
                    "Idle time: %.1f min\nHarsh events: %d\nTelemetry points: %d",
                    idleMillis / 60000.0,
                    harshEvents,
                    records.size
                )
                txtMetrics.text = metricsText

                val entries = records.mapIndexedNotNull { index, rec ->
                    val speed = rec.speed ?: return@mapIndexedNotNull null
                    Entry(index.toFloat(), speed.toFloat())
                }
                if (entries.isNotEmpty()) {
                    val dataSet = LineDataSet(entries, "Speed (km/h)")
                    dataSet.lineWidth = 2f
                    dataSet.setDrawCircles(false)
                    dataSet.setDrawValues(false)
                    val lineData = LineData(dataSet)
                    chart.data = lineData
                    chart.description = Description().apply { text = "" }
                    chart.invalidate()
                } else {
                    chart.clear()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.events.collectLatest { events ->
                if (events.isEmpty()) {
                    txtEvents.text = getString(R.string.driving_events_none)
                } else {
                    val builder = StringBuilder()
                    for (e in events) {
                        builder.append("${e.type} at ${e.value} (severity ${e.severity})\n")
                    }
                    txtEvents.text = builder.toString().trimEnd()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.alerts.collectLatest { alerts ->
                if (alerts.isEmpty()) {
                    txtAlerts.text = getString(R.string.vehicle_alerts_none)
                } else {
                    val builder = StringBuilder()
                    for (a in alerts) {
                        builder.append("[${a.severity}] ${a.message}\n")
                    }
                    txtAlerts.text = builder.toString().trimEnd()
                }
            }
        }

        viewModel.load()
    }
}
