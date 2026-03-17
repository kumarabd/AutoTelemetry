package org.nighthawklabs.telemetry.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.nighthawklabs.telemetry.R
import org.nighthawklabs.telemetry.data.local.AppDatabase
import org.nighthawklabs.telemetry.data.repository.DrivingTripRepository
import org.nighthawklabs.telemetry.data.repository.TelemetryRepository
import org.nighthawklabs.telemetry.domain.TripSummary
import org.nighthawklabs.telemetry.viewmodel.TripHistoryViewModel

class TripHistoryActivity : ComponentActivity() {

    private val viewModel: TripHistoryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = Room.databaseBuilder(
                    applicationContext,
                    AppDatabase::class.java,
                    "telemetry.db"
                )
                    .addMigrations(AppDatabase.MIGRATION_1_2)
                    .build()
                val tripRepo = DrivingTripRepository(db.drivingTripDao())
                val telemetryRepo = TelemetryRepository(db.telemetryDao())
                return TripHistoryViewModel(tripRepo, telemetryRepo) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)

        val recyclerView: RecyclerView = findViewById(R.id.recyclerTrips)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val adapter = TripHistoryAdapter { summary ->
            val intent = Intent(this, TripDetailActivity::class.java)
            intent.putExtra(TripDetailActivity.EXTRA_TRIP_ID, summary.tripId)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            viewModel.tripSummaries.collectLatest { summaries ->
                adapter.submitList(summaries)
            }
        }

        viewModel.loadTrips()
    }
}

private class TripHistoryAdapter(
    private val onTripClicked: (TripSummary) -> Unit
) : RecyclerView.Adapter<TripHistoryViewHolder>() {

    private var items: List<TripSummary> = emptyList()

    fun submitList(newItems: List<TripSummary>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripHistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_history, parent, false)
        return TripHistoryViewHolder(view as ViewGroup, onTripClicked)
    }

    override fun onBindViewHolder(holder: TripHistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

private class TripHistoryViewHolder(
    private val root: ViewGroup,
    private val onTripClicked: (TripSummary) -> Unit
) : RecyclerView.ViewHolder(root) {

    private val title: TextView = root.findViewById(R.id.txtTripTitle)
    private val subtitle: TextView = root.findViewById(R.id.txtTripSubtitle)

    fun bind(summary: TripSummary) {
        val durationMin = summary.durationMillis / 60000.0
        val titleText = String.format(
            "%tF • %.0f min • %.1f km",
            summary.startTime,
            durationMin,
            summary.distanceKm
        )
        val subtitleText = String.format(
            "Avg %.0f km/h • Max %.0f km/h • %d pts",
            summary.avgSpeedKmh,
            summary.maxSpeedKmh,
            summary.recordCount
        )
        title.text = titleText
        subtitle.text = subtitleText

        root.setOnClickListener { onTripClicked(summary) }
    }
}

