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
        ).addMigrations(MIGRATION_1_2).build()

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
}
