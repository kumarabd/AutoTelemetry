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

        try {
            FirebaseApp.initializeApp(this)
            Log.i(TAG, "FirebaseApp initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseApp", e)
        }

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "telemetry.db"
        )
            .fallbackToDestructiveMigration()
            .build()

        TelemetrySyncScheduler.enqueuePeriodicSync(this)
    }
}
