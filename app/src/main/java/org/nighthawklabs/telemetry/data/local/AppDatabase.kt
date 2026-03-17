package org.nighthawklabs.telemetry.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TelemetryEntity::class, DrivingTripEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
    abstract fun drivingTripDao(): DrivingTripDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add tripId column to existing telemetry table
                database.execSQL("ALTER TABLE telemetry ADD COLUMN tripId TEXT")

                // Create driving_trips table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS driving_trips (
                        tripId TEXT NOT NULL PRIMARY KEY,
                        vehicleId TEXT,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        status TEXT NOT NULL,
                        startLatitude REAL,
                        startLongitude REAL,
                        endLatitude REAL,
                        endLongitude REAL,
                        totalRecordCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Indices for driving_trips
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_driving_trips_status ON driving_trips(status)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_driving_trips_startedAt ON driving_trips(startedAt)"
                )
            }
        }
    }
}

