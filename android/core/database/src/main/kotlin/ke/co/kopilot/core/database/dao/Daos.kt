package ke.co.kopilot.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ke.co.kopilot.core.database.entity.CachedWeatherEntity
import ke.co.kopilot.core.database.entity.HeartRateMeasurementEntity
import ke.co.kopilot.core.database.entity.RoadObservationEntity
import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.core.database.entity.SleepSessionEntity
import ke.co.kopilot.core.database.entity.SleepStageEntity
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.database.entity.VehicleProfileEntity
import ke.co.kopilot.core.database.entity.WearableConnectionEventEntity
import ke.co.kopilot.core.database.entity.WearableDeviceEntity
import ke.co.kopilot.core.database.entity.WearableSyncHistoryEntity
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

@Dao
interface WearableDao {
    // Wearable Devices
    @Query("SELECT * FROM wearable_devices ORDER BY isLastSelected DESC, updatedAtMillis DESC")
    fun observeDevices(): Flow<List<WearableDeviceEntity>>

    @Query("SELECT * FROM wearable_devices WHERE isLastSelected = 1 LIMIT 1")
    suspend fun getLastSelectedDevice(): WearableDeviceEntity?

    @Query("SELECT * FROM wearable_devices WHERE deviceAddress = :address LIMIT 1")
    suspend fun getDeviceByAddress(address: String): WearableDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: WearableDeviceEntity)

    @Query("UPDATE wearable_devices SET isLastSelected = CASE WHEN deviceAddress = :selectedAddress THEN 1 ELSE 0 END")
    suspend fun markDeviceAsSelected(selectedAddress: String)

    @Query("DELETE FROM wearable_devices WHERE deviceAddress = :address")
    suspend fun deleteDevice(address: String)

    // Heart-Rate Measurements (IGNORE on unique (deviceAddress, timestampMillis) prevents duplicates)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartRateBatch(samples: List<HeartRateMeasurementEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartRate(sample: HeartRateMeasurementEntity): Long

    @Query("SELECT * FROM heart_rate_measurements WHERE isValid = 1 ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecentHeartRates(limit: Int = 120): Flow<List<HeartRateMeasurementEntity>>

    @Query("SELECT * FROM heart_rate_measurements WHERE tripId = :tripId AND isValid = 1 ORDER BY timestampMillis ASC")
    suspend fun getHeartRatesForTrip(tripId: String): List<HeartRateMeasurementEntity>

    @Query("SELECT * FROM heart_rate_measurements WHERE timestampMillis BETWEEN :startMillis AND :endMillis AND isValid = 1 ORDER BY timestampMillis ASC")
    suspend fun getHeartRatesBetween(startMillis: Long, endMillis: Long): List<HeartRateMeasurementEntity>

    @Query("DELETE FROM heart_rate_measurements WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteHeartRatesOlderThan(cutoffMillis: Long)

    // Sleep Sessions & Stages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleepSession(session: SleepSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepStages(stages: List<SleepStageEntity>)

    @Query("DELETE FROM sleep_stages WHERE sessionId = :sessionId")
    suspend fun deleteStagesForSession(sessionId: String)

    @Query("SELECT * FROM sleep_sessions ORDER BY endTimeMillis DESC LIMIT :limit")
    fun observeSleepSessions(limit: Int = 14): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions ORDER BY endTimeMillis DESC LIMIT 1")
    suspend fun getLatestSleepSession(): SleepSessionEntity?

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY stageStartMillis ASC")
    suspend fun getStagesForSession(sessionId: String): List<SleepStageEntity>

    @Query("SELECT * FROM sleep_stages ORDER BY stageStartMillis DESC LIMIT 200")
    fun observeRecentSleepStages(): Flow<List<SleepStageEntity>>

    // Sync History & Connection Events
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncRecord(record: WearableSyncHistoryEntity)

    @Query("SELECT * FROM wearable_sync_history ORDER BY syncedAtMillis DESC LIMIT :limit")
    fun observeSyncHistory(limit: Int = 20): Flow<List<WearableSyncHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectionEvent(event: WearableConnectionEventEntity)

    @Query("SELECT * FROM wearable_connection_events ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeConnectionEvents(limit: Int = 30): Flow<List<WearableConnectionEventEntity>>

    @Query("SELECT * FROM wearable_connection_events WHERE tripId = :tripId ORDER BY timestampMillis ASC")
    suspend fun getConnectionEventsForTrip(tripId: String): List<WearableConnectionEventEntity>

    // User-Controlled Data Deletion
    @Query("DELETE FROM heart_rate_measurements")
    suspend fun deleteAllHeartRates()

    @Query("DELETE FROM sleep_stages")
    suspend fun deleteAllSleepStages()

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAllSleepSessions()

    @Query("DELETE FROM wearable_sync_history")
    suspend fun deleteAllSyncHistory()

    @Query("DELETE FROM wearable_connection_events")
    suspend fun deleteAllConnectionEvents()

    @Query("DELETE FROM wearable_devices")
    suspend fun deleteAllWearableDevices()
}
