package ke.co.kopilot.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ke.co.kopilot.core.database.entity.CachedWeatherEntity
import ke.co.kopilot.core.database.entity.RoadObservationEntity
import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.database.entity.VehicleProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeTrips(limit: Int = 100): Flow<List<TripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE startedAtMillis < :cutoffMillis")
    suspend fun deleteTripsOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()
}

@Dao
interface VehicleProfileDao {
    @Query("SELECT * FROM vehicle_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActiveVehicle(): Flow<VehicleProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVehicle(vehicle: VehicleProfileEntity)
}

@Dao
interface CachedWeatherDao {
    @Query("SELECT * FROM cached_weather ORDER BY fetchedAtMillis DESC LIMIT 1")
    fun observeLatestWeather(): Flow<CachedWeatherEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeather(weather: CachedWeatherEntity)

    @Query("DELETE FROM cached_weather")
    suspend fun clearAll()
}

@Dao
interface RoadObservationDao {
    @Query("SELECT * FROM road_observations ORDER BY observedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RoadObservationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObservation(observation: RoadObservationEntity)

    @Query("DELETE FROM road_observations")
    suspend fun clearAll()
}

@Dao
interface SensorHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: SensorHistoryEntity)

    @Query("SELECT * FROM sensor_history WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC LIMIT :limit")
    suspend fun getSamplesSince(sinceMillis: Long, limit: Int = 500): List<SensorHistoryEntity>

    @Query("SELECT * FROM sensor_history WHERE timestampMillis BETWEEN :startMillis AND :endMillis ORDER BY timestampMillis ASC LIMIT :limit")
    suspend fun getSamplesBetween(startMillis: Long, endMillis: Long, limit: Int = 500): List<SensorHistoryEntity>

    @Query("SELECT * FROM sensor_history ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecentSamples(limit: Int = 120): Flow<List<SensorHistoryEntity>>

    @Query("DELETE FROM sensor_history WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
