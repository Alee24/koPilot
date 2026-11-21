package ke.co.kopilot.feature.trips

import com.google.android.gms.maps.model.LatLng

enum class MeasurementProvenance(val label: String) {
    DIRECTLY_MEASURED("Directly Measured"),
    CALCULATED_FROM_MEASURED("Calculated"),
    PROVIDER_REPORTED("Provider Reported"),
    ESTIMATED("Estimated"),
    UNAVAILABLE("Unavailable"),
}

data class MeasuredMetric<T>(
    val label: String,
    val value: T?,
    val formattedValue: String,
    val unit: String = "",
    val source: String,
    val provenance: MeasurementProvenance,
    val timestampMillis: Long? = null,
    val qualityNote: String = "",
)

data class TripReportSample(
    val timestampMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val gpsSpeedKmh: Float? = null,
    val bearingDeg: Float? = null,
    val obdSpeedKmh: Float? = null,
    val engineRpm: Int? = null,
    val coolantTempC: Int? = null,
    val engineLoadPct: Float? = null,
    val throttlePct: Float? = null,
    val mafGramsPerSec: Float? = null,
    val intakeTempC: Int? = null,
    val voltageV: Float? = null,
    val heartRateBpm: Int? = null,
    val accelMagG: Float? = null,
    val gyroDegPerSec: Float? = null,
)

enum class TimelineEventCategory(val label: String) {
    DRIVING_BEHAVIOR("Driving"),
    ROAD_INTELLIGENCE("Road"),
    VEHICLE_DIAGNOSTICS("Vehicle"),
    SAFETY_INCIDENT("Safety"),
    SYSTEM_INTERRUPTION("System"),
}

data class TripTimelineEvent(
    val id: String,
    val timestampMillis: Long,
    val category: TimelineEventCategory,
    val title: String,
    val description: String,
    val severity: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sensorContext: String = "",
    val mediaPath: String? = null,
    val confidence: String = "",
)

data class ScoreComponent(
    val name: String,
    val score: Int?,
    val weightPercent: Int,
    val explanation: String,
    val provenance: MeasurementProvenance,
)

data class TripScoreBreakdown(
    val overallScore: Int?,
    val summaryNote: String,
    val components: List<ScoreComponent>,
)

data class TripIntelligenceReport(
    val tripId: String,
    val statusLabel: String,
    val accidentBannerTitle: String,
    val accidentBannerDetail: String,
    val warnings: List<String>,
    val sensorCoverageSummary: List<Pair<String, String>>,
    val originLabel: String,
    val destinationLabel: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val durationSeconds: Long,
    val vehicleName: String,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val idleTimeSeconds: Long,
    val stopCount: Int,
    val avgMovingSpeedKmh: Float?,
    val avgTripSpeedKmh: Float?,
    val maxSpeedKmh: Float?,
    val gpsQualityLabel: String,
    val routePoints: List<LatLng>,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val scoreBreakdown: TripScoreBreakdown,
    val obdMetrics: List<MeasuredMetric<String>>,
    val motionMetrics: List<MeasuredMetric<String>>,
    val safetyMetrics: List<MeasuredMetric<String>>,
    val wellnessMetrics: List<MeasuredMetric<String>>,
    val cameraMetrics: List<MeasuredMetric<String>>,
    val weatherMetrics: List<MeasuredMetric<String>>,
    val performanceMetrics: List<MeasuredMetric<String>>,
    val samples: List<TripReportSample>,
    val events: List<TripTimelineEvent>,
    val mediaItems: List<TripTimelineEvent>,
    val dataCompletenessPercent: Int,
)
