package ke.co.kopilot.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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
    // Phase 6 & 7: Smartwatch Heart-Rate & Pre-Drive Sleep Context associated with the trip
    val avgHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val heartRateSamplesCount: Int = 0,
    val watchDisconnectedGapsCount: Int = 0,
    val preDriveSleepMinutes: Int? = null,
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

@Entity(tableName = "sensor_history")
data class SensorHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long,
    val tripId: String?,
    val obdConnected: Boolean,
    val rpm: Int?,
    val coolantTempC: Int?,
    val voltageV: Float?,
    val engineLoadPct: Float?,
    val throttlePct: Float?,
    val intakeTempC: Int?,
    val mafGramsPerSec: Float?,
    val fuelRateLph: Float?,
    val speedKmh: Float?,
    val safetyScore: Int,
    val fuelScore: Int,
)

@Entity(
    tableName = "wearable_devices",
    indices = [
        Index(value = ["isLastSelected"]),
        Index(value = ["lastConnectedAtMillis"]),
    ],
)
data class WearableDeviceEntity(
    @PrimaryKey val deviceAddress: String,
    val deviceName: String,
    val manufacturerName: String?,
    val modelNumber: String?,
    val firmwareRevision: String?,
    val supportsHeartRateService: Boolean,
    val supportsBatteryService: Boolean,
    val lastBatteryPercent: Int?,
    val lastRssiDbm: Int?,
    val discoveredServicesCsv: String,
    val isLastSelected: Boolean,
    val firstPairedAtMillis: Long,
    val lastConnectedAtMillis: Long?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "heart_rate_measurements",
    indices = [
        Index(value = ["deviceAddress", "timestampMillis"], unique = true),
        Index(value = ["tripId"]),
        Index(value = ["timestampMillis"]),
    ],
)
data class HeartRateMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val deviceAddress: String,
    val deviceName: String,
    val tripId: String?,
    val bpm: Int,
    val timestampMillis: Long,
    val dataSource: String,
    val connectionStatus: String,
    val isValid: Boolean,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean?,
    val energyExpendedKj: Int?,
    val latestRrIntervalMs: Int?,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["startTimeMillis", "endTimeMillis"], unique = true),
        Index(value = ["endTimeMillis"]),
        Index(value = ["sourcePackage"]),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey val sessionId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val timeZoneOffsetSeconds: Int,
    val totalSleepDurationMinutes: Int,
    val timeInBedMinutes: Int,
    val sleepEfficiencyPercent: Int,
    val deepSleepMinutes: Int?,
    val lightSleepMinutes: Int?,
    val remSleepMinutes: Int?,
    val awakeMinutes: Int?,
    val interruptionsCount: Int,
    val stagesAvailable: Boolean,
    val sourcePackage: String,
    val sourceAppName: String,
    val title: String?,
    val notes: String?,
    val syncedAtMillis: Long,
)

@Entity(
    tableName = "sleep_stages",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "stageStartMillis"], unique = true),
        Index(value = ["sessionId"]),
    ],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val stageType: String,
    val stageStartMillis: Long,
    val stageEndMillis: Long,
    val durationMinutes: Int,
)

@Entity(
    tableName = "wearable_sync_history",
    indices = [
        Index(value = ["syncedAtMillis"]),
        Index(value = ["syncSource"]),
    ],
)
data class WearableSyncHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val syncSource: String,
    val syncType: String,
    val status: String,
    val recordsImportedCount: Int,
    val detailMessage: String,
    val syncedAtMillis: Long,
)

@Entity(
    tableName = "wearable_connection_events",
    indices = [
        Index(value = ["deviceAddress", "timestampMillis"]),
        Index(value = ["tripId"]),
    ],
)
data class WearableConnectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val deviceAddress: String,
    val deviceName: String?,
    val tripId: String?,
    val eventType: String,
    val detail: String,
    val timestampMillis: Long,
)
