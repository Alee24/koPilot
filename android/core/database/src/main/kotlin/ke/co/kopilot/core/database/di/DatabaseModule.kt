package ke.co.kopilot.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ke.co.kopilot.core.database.KoPilotDatabase
import ke.co.kopilot.core.database.dao.CachedWeatherDao
import ke.co.kopilot.core.database.dao.RoadObservationDao
import ke.co.kopilot.core.database.dao.TripDao
import ke.co.kopilot.core.database.dao.VehicleProfileDao
import javax.inject.Singleton

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
        ).fallbackToDestructiveMigration(dropAllTables = true).build()

    @Provides
    fun provideTripDao(db: KoPilotDatabase): TripDao = db.tripDao()

    @Provides
    fun provideVehicleProfileDao(db: KoPilotDatabase): VehicleProfileDao = db.vehicleProfileDao()

    @Provides
    fun provideCachedWeatherDao(db: KoPilotDatabase): CachedWeatherDao = db.cachedWeatherDao()

    @Provides
    fun provideRoadObservationDao(db: KoPilotDatabase): RoadObservationDao = db.roadObservationDao()
}
