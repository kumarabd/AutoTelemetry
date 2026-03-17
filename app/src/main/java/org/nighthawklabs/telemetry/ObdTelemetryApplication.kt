package org.nighthawklabs.telemetry

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.google.firebase.FirebaseApp
import org.nighthawklabs.telemetry.data.local.AppDatabase
import org.nighthawklabs.telemetry.sync.TelemetrySyncScheduler

private const val TAG = "ObdTelemetryApplication"

class ObdTelemetryApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize Room Database
        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "telemetry.db"
        )
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()

        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }

        // Schedule periodic telemetry sync
        TelemetrySyncScheduler.enqueuePeriodicSync(this)
    }
}
