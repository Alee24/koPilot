package ke.co.kopilot.voice

import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.SpeedAlertEngine
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vision.RoadVisionPipeline
import ke.co.kopilot.feature.voice.VoiceToolExecutor
import ke.co.kopilot.feature.weather.WeatherRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KoPilotVoiceToolExecutor @Inject constructor(
    private val locationTracker: LocationTracker,
    private val speedAlertEngine: SpeedAlertEngine,
    private val navigationRepo: NavigationRepository,
    private val trafficAdvisor: TrafficAdvisor,
    private val weatherRepo: WeatherRepository,
    private val visionPipeline: RoadVisionPipeline,
    private val obdManager: ObdBluetoothManager,
    private val tripRepo: TripRepository,
) : VoiceToolExecutor {

    override suspend fun executeTool(name: String, arguments: JsonObject): String = when (name) {
        "get_current_location" -> {
            val fix = locationTracker.state.value.currentFix
            if (fix == null) {
                """{"status":"unavailable","reason":"GPS fix not available"}"""
            } else {
                """{"status":"ok","latitude":${fix.latitude},"longitude":${fix.longitude},"accuracy_m":${fix.horizontalAccuracyMeters ?: -1}}"""
            }
        }
        "get_current_speed" -> {
            val vision = visionPipeline.state.value
            val eval = speedAlertEngine.evaluate(
                fix = locationTracker.state.value.currentFix,
                latestSign = vision.lastObservation,
                thresholdKmh = 5,
            )
            val observedLimit = vision.lastSpeedLimitKmh ?: eval.applicableSpeedLimitKmh
            """{"status":"${eval.signalState.name}","speed_kmh":${eval.currentSpeedKmh ?: "null"},"observed_limit_kmh":${observedLimit ?: "null"},"last_sign":"${vision.lastObservation?.category?.displayName ?: "none"}","decision":"${eval.decision.name}"}"""
        }
        "get_active_route" -> {
            val nav = navigationRepo.state.value
            val r = nav.activeRoute
            if (r == null) {
                """{"status":"unavailable","reason":"No active navigation route"}"""
            } else {
                """{"status":"ok","destination":"${nav.destinationName}","eta_minutes":${r.durationSeconds / 60},"distance_km":${"%.1f".format(r.distanceMeters / 1000.0)},"next_instruction":"${r.steps.firstOrNull()?.instruction ?: "Continue"}"}"""
            }
        }
        "get_traffic_conditions" -> {
            val nav = navigationRepo.state.value
            val eval = trafficAdvisor.evaluate(nav.activeRoute, nav.alternativeRoutes)
            """{"status":"${if (nav.activeRoute != null) "ok" else "unavailable"}","traffic_status":"${eval.statusLabel}","delay_minutes":${eval.delayMinutes}}"""
        }
        "find_alternative_routes" -> {
            val nav = navigationRepo.state.value
            val eval = trafficAdvisor.evaluate(nav.activeRoute, nav.alternativeRoutes)
            if (eval.recommendedAlternative == null) {
                """{"status":"ok","alternative_available":false,"reason":"No alternative route saves meaningful time"}"""
            } else {
                """{"status":"ok","alternative_available":true,"summary":"${eval.recommendedAlternative?.summary}","minutes_saved":${eval.estimatedMinutesSaved}}"""
            }
        }
        "get_weather_forecast" -> {
            val w = weatherRepo.state.value
            if (!w.available) {
                """{"status":"unavailable"}"""
            } else {
                """{"status":"ok","condition":"${w.conditionSummary}","temperature_c":${w.temperatureC ?: "null"},"rain_probability_percent":${w.rainProbabilityPercent ?: "null"},"cached_offline":${w.isStaleOfflineCache}}"""
            }
        }
        "search_nearby_places" -> {
            val query = arguments["query"]?.jsonPrimitive?.content ?: "petrol station"
            val fix = locationTracker.state.value.currentFix
            val results = navigationRepo.searchPlaces(query, fix?.latitude, fix?.longitude)
            if (results.isEmpty()) {
                """{"status":"unavailable","query":"$query"}"""
            } else {
                val top = results.first()
                """{"status":"ok","nearest_name":"${top.name}","address":"${top.formattedAddress}","count":${results.size}}"""
            }
        }
        "get_recent_road_observations" -> {
            val obs = visionPipeline.state.value.lastObservation
            if (obs == null) {
                """{"status":"unavailable","reason":"No traffic signs observed recently"}"""
            } else {
                """{"status":"ok","sign":"${obs.category.displayName}","confidence":${obs.confidence},"advisory_only":true}"""
            }
        }
        "get_vehicle_diagnostics" -> {
            val obd = obdManager.state.value
            if (!obd.obdConnected) {
                """{"status":"unavailable","reason":"${obd.statusMessage}"}"""
            } else {
                """{"status":"ok","rpm":${obd.engineRpm ?: "null"},"coolant_c":${obd.coolantTempC ?: "null"},"dtc_count":${obd.dtcCodes.size},"tpms":"${obd.tpmsStatusMessage}"}"""
            }
        }
        "get_trip_summary" -> {
            val distKm = locationTracker.state.value.distanceTraveledMeters / 1000.0
            val savedCount = tripRepo.trips.firstOrNull()?.size ?: 0
            """{"status":"ok","current_trip_distance_km":${"%.2f".format(distKm)},"saved_trips_count":$savedCount}"""
        }
        else -> """{"status":"unavailable","reason":"Unknown tool $name"}"""
    }
}
