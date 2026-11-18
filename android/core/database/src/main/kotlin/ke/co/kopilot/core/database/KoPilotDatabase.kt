package ke.co.kopilot.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import ke.co.kopilot.core.database.dao.CachedWeatherDao
import ke.co.kopilot.core.database.dao.RoadObservationDao
import ke.co.kopilot.core.database.dao.SensorHistoryDao
import ke.co.kopilot.core.database.dao.TripDao
import ke.co.kopilot.core.database.dao.VehicleProfileDao
import ke.co.kopilot.core.database.entity.CachedWeatherEntity
import ke.co.kopilot.core.database.entity.RoadObservationEntity
import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.database.entity.VehicleProfileEntity

@Database(
    entities = [
        TripEntity::class,
        VehicleProfileEntity::class,
        CachedWeatherEntity::class,
        RoadObservationEntity::class,
        SensorHistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class KoPilotDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun vehicleProfileDao(): VehicleProfileDao
    abstract fun cachedWeatherDao(): CachedWeatherDao
    abstract fun roadObservationDao(): RoadObservationDao
    abstract fun sensorHistoryDao(): SensorHistoryDao
}
