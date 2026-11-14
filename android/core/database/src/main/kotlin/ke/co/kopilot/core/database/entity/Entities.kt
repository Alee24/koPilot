package ke.co.kopilot.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val originLabel: String?,
    val destinationLabel: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val distanceKm: Double,
    val durationSeconds: Long,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val signsDetectedCount: Int,
    val alertsTriggeredCount: Int,
    val syncedToBackend: Boolean = false,
)

@Entity(tableName = "vehicle_profiles")
data class VehicleProfileEntity(
    @PrimaryKey val id: String,
    val manufacturer: String,
    val model: String,
    val modelYear: Int?,
    val engineType: String,
    val transmission: String,
    val fuelType: String,
    val obdAdapterAddress: String?,
    val coolantWarningThresholdC: Int,
    val isActive: Boolean = true,
)

@Entity(tableName = "cached_weather")
data class CachedWeatherEntity(
    @PrimaryKey val locationKey: String,
    val latitude: Double,
    val longitude: Double,
    val temperatureC: Double?,
    val windSpeedKmh: Double?,
    val precipitationMm: Double?,
    val rainProbabilityPercent: Int?,
    val conditionSummary: String,
    val coverageNote: String,
    val fetchedAtMillis: Long,
)

@Entity(tableName = "road_observations")
data class RoadObservationEntity(
    @PrimaryKey val id: String,
    val tripId: String?,
    val categoryName: String,
    val confidence: Float,
    val speedLimitKmh: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val observedAtMillis: Long,
)
