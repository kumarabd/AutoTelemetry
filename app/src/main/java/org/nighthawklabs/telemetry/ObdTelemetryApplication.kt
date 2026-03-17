package org.nighthawklabs.telemetry

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import org.nighthawklabs.telemetry.sync.TelemetrySyncScheduler

private const val TAG = "ObdTelemetryApplication"

class ObdTelemetryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // NOTE: Ensure google-services.json is added under app/ and
        // the Google Services Gradle plugin is applied to enable Firebase.
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }

        // Schedule periodic telemetry sync
        TelemetrySyncScheduler.enqueuePeriodicSync(this)
    }
}

