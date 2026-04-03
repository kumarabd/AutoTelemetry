package org.nighthawklabs.telemetry.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TelemetryEntity::class, DrivingTripEntity::class, VehicleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
    abstract fun drivingTripDao(): DrivingTripDao
    abstract fun vehicleDao(): VehicleDao

    companion object
}
