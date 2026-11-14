package ke.co.kopilot.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VoiceSessionRequestDto(
    @SerialName("device_id") val deviceId: String,
    val language: String = "en",
    val voice: String? = null,
)

@Serializable
data class VoiceSessionResponseDto(
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("expires_at") val expiresAt: Long,
    val model: String,
    val voice: String,
    @SerialName("webrtc_calls_url") val webrtcCallsUrl: String,
)

@Serializable
data class LatLngDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class RouteComputeRequestDto(
    val origin: LatLngDto,
    val destination: LatLngDto,
    @SerialName("avoid_tolls") val avoidTolls: Boolean = false,
    @SerialName("avoid_highways") val avoidHighways: Boolean = false,
    @SerialName("compute_alternatives") val computeAlternatives: Boolean = true,
)

@Serializable
data class RouteStepSummaryDto(
    val instruction: String,
    @SerialName("distance_meters") val distanceMeters: Int,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val maneuver: String? = null,
)

@Serializable
data class RouteOptionDto(
    @SerialName("route_id") val routeId: String,
    val summary: String,
    @SerialName("distance_meters") val distanceMeters: Int,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("static_duration_seconds") val staticDurationSeconds: Int,
    @SerialName("traffic_delay_seconds") val trafficDelaySeconds: Int,
    @SerialName("encoded_polyline") val encodedPolyline: String,
    val steps: List<RouteStepSummaryDto> = emptyList(),
)

@Serializable
data class RouteComputeResponseDto(
    val provider: String,
    val routes: List<RouteOptionDto>,
    @SerialName("fetched_at") val fetchedAt: String,
)

@Serializable
data class WeatherHourlyItemDto(
    val time: String? = null,
    @SerialName("temperature_c") val temperatureC: Double? = null,
    @SerialName("rain_probability_percent") val rainProbabilityPercent: Int? = null,
    @SerialName("precipitation_mm") val precipitationMm: Double? = null,
    @SerialName("wind_speed_kmh") val windSpeedKmh: Double? = null,
    @SerialName("visibility_meters") val visibilityMeters: Double? = null,
)

@Serializable
data class WeatherResponseDto(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("temperature_c") val temperatureC: Double? = null,
    @SerialName("wind_speed_kmh") val windSpeedKmh: Double? = null,
    @SerialName("precipitation_mm") val precipitationMm: Double? = null,
    @SerialName("rain_probability_percent") val rainProbabilityPercent: Int? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("condition_summary") val conditionSummary: String,
    @SerialName("visibility_meters") val visibilityMeters: Double? = null,
    @SerialName("severe_weather_coverage_available") val severeWeatherCoverageAvailable: Boolean = false,
    @SerialName("coverage_note") val coverageNote: String,
    @SerialName("hourly_forecast") val hourlyForecast: List<WeatherHourlyItemDto> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: String,
    val cached: Boolean = false,
)

@Serializable
data class PlaceItemDto(
    @SerialName("place_id") val placeId: String,
    val name: String,
    @SerialName("formatted_address") val formattedAddress: String,
    val latitude: Double,
    val longitude: Double,
    val types: List<String> = emptyList(),
)

@Serializable
data class PlacesSearchResponseDto(
    val provider: String,
    val query: String,
    val results: List<PlaceItemDto>,
    @SerialName("fetched_at") val fetchedAt: String,
)

@Serializable
data class TripCreateRequestDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("origin_label") val originLabel: String? = null,
    @SerialName("destination_label") val destinationLabel: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("max_speed_kmh") val maxSpeedKmh: Float,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Float,
    @SerialName("signs_detected_count") val signsDetectedCount: Int,
    @SerialName("alerts_triggered_count") val alertsTriggeredCount: Int,
)

@Serializable
data class TripResponseDto(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("origin_label") val originLabel: String? = null,
    @SerialName("destination_label") val destinationLabel: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("max_speed_kmh") val maxSpeedKmh: Float,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Float,
    @SerialName("signs_detected_count") val signsDetectedCount: Int,
    @SerialName("alerts_triggered_count") val alertsTriggeredCount: Int,
    @SerialName("created_at") val createdAt: String,
)
