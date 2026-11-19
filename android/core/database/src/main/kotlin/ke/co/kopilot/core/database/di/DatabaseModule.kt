package ke.co.kopilot.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ke.co.kopilot.core.database.KoPilotDatabase
import ke.co.kopilot.core.database.dao.CachedWeatherDao
import ke.co.kopilot.core.database.dao.RoadObservationDao
import ke.co.kopilot.core.database.dao.SensorHistoryDao
import ke.co.kopilot.core.database.dao.TripDao
import ke.co.kopilot.core.database.dao.VehicleProfileDao
import ke.co.kopilot.core.database.dao.WearableDao
import javax.inject.Singleton

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sensor_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestampMillis INTEGER NOT NULL,
                tripId TEXT,
                obdConnected INTEGER NOT NULL,
                rpm INTEGER,
                coolantTempC INTEGER,
                voltageV REAL,
                engineLoadPct REAL,
                throttlePct REAL,
                intakeTempC INTEGER,
                mafGramsPerSec REAL,
                fuelRateLph REAL,
                speedKmh REAL,
                safetyScore INTEGER NOT NULL,
                fuelScore INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Safely add smartwatch heart-rate & pre-drive sleep columns to trips without losing existing trip records
        db.execSQL("ALTER TABLE trips ADD COLUMN avgHeartRateBpm INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN minHeartRateBpm INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN maxHeartRateBpm INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN heartRateSamplesCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN watchDisconnectedGapsCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN preDriveSleepMinutes INTEGER")

        // 1. Connected Wearable Devices
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wearable_devices (
                deviceAddress TEXT NOT NULL PRIMARY KEY,
                deviceName TEXT NOT NULL,
                manufacturerName TEXT,
                modelNumber TEXT,
                firmwareRevision TEXT,
                supportsHeartRateService INTEGER NOT NULL,
                supportsBatteryService INTEGER NOT NULL,
                lastBatteryPercent INTEGER,
                lastRssiDbm INTEGER,
                discoveredServicesCsv TEXT NOT NULL,
                isLastSelected INTEGER NOT NULL,
                firstPairedAtMillis INTEGER NOT NULL,
                lastConnectedAtMillis INTEGER,
                updatedAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_devices_isLastSelected ON wearable_devices(isLastSelected)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_devices_lastConnectedAtMillis ON wearable_devices(lastConnectedAtMillis)")

        // 2. Heart-Rate Measurements
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS heart_rate_measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                deviceAddress TEXT NOT NULL,
                deviceName TEXT NOT NULL,
                tripId TEXT,
                bpm INTEGER NOT NULL,
                timestampMillis INTEGER NOT NULL,
                dataSource TEXT NOT NULL,
                connectionStatus TEXT NOT NULL,
                isValid INTEGER NOT NULL,
                sensorContactSupported INTEGER NOT NULL,
                sensorContactDetected INTEGER,
                energyExpendedKj INTEGER,
                latestRrIntervalMs INTEGER,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_heart_rate_measurements_deviceAddress_timestampMillis ON heart_rate_measurements(deviceAddress, timestampMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_heart_rate_measurements_tripId ON heart_rate_measurements(tripId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_heart_rate_measurements_timestampMillis ON heart_rate_measurements(timestampMillis)")

        // 3. Sleep Sessions
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sleep_sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                startTimeMillis INTEGER NOT NULL,
                endTimeMillis INTEGER NOT NULL,
                timeZoneOffsetSeconds INTEGER NOT NULL,
                totalSleepDurationMinutes INTEGER NOT NULL,
                timeInBedMinutes INTEGER NOT NULL,
                sleepEfficiencyPercent INTEGER NOT NULL,
                deepSleepMinutes INTEGER,
                lightSleepMinutes INTEGER,
                remSleepMinutes INTEGER,
                awakeMinutes INTEGER,
                interruptionsCount INTEGER NOT NULL,
                stagesAvailable INTEGER NOT NULL,
                sourcePackage TEXT NOT NULL,
                sourceAppName TEXT NOT NULL,
                title TEXT,
                notes TEXT,
                syncedAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sleep_sessions_startTimeMillis_endTimeMillis ON sleep_sessions(startTimeMillis, endTimeMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sleep_sessions_endTimeMillis ON sleep_sessions(endTimeMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sleep_sessions_sourcePackage ON sleep_sessions(sourcePackage)")

        // 4. Sleep Stages (with CASCADE foreign key on sessionId)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sleep_stages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                stageType TEXT NOT NULL,
                stageStartMillis INTEGER NOT NULL,
                stageEndMillis INTEGER NOT NULL,
                durationMinutes INTEGER NOT NULL,
                FOREIGN KEY(sessionId) REFERENCES sleep_sessions(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sleep_stages_sessionId_stageStartMillis ON sleep_stages(sessionId, stageStartMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sleep_stages_sessionId ON sleep_stages(sessionId)")

        // 5. Wearable Synchronization History
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wearable_sync_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                syncSource TEXT NOT NULL,
                syncType TEXT NOT NULL,
                status TEXT NOT NULL,
                recordsImportedCount INTEGER NOT NULL,
                detailMessage TEXT NOT NULL,
                syncedAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_sync_history_syncedAtMillis ON wearable_sync_history(syncedAtMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_sync_history_syncSource ON wearable_sync_history(syncSource)")

        // 6. Wearable Connection Events
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wearable_connection_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                deviceAddress TEXT NOT NULL,
                deviceName TEXT,
                tripId TEXT,
                eventType TEXT NOT NULL,
                detail TEXT NOT NULL,
                timestampMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_connection_events_deviceAddress_timestampMillis ON wearable_connection_events(deviceAddress, timestampMillis)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wearable_connection_events_tripId ON wearable_connection_events(tripId)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KoPilotDatabase =
        Room.databaseBuilder(
            context,
            KoPilotDatabase::class.java,
            "kopilot.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideTripDao(db: KoPilotDatabase): TripDao = db.tripDao()

    @Provides
    fun provideVehicleProfileDao(db: KoPilotDatabase): VehicleProfileDao = db.vehicleProfileDao()

    @Provides
    fun provideCachedWeatherDao(db: KoPilotDatabase): CachedWeatherDao = db.cachedWeatherDao()

    @Provides
    fun provideRoadObservationDao(db: KoPilotDatabase): RoadObservationDao = db.roadObservationDao()

    @Provides
    fun provideSensorHistoryDao(db: KoPilotDatabase): SensorHistoryDao = db.sensorHistoryDao()

    @Provides
    fun provideWearableDao(db: KoPilotDatabase): WearableDao = db.wearableDao()
}
