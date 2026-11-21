package ke.co.kopilot.voice

import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.database.preferences.VoiceMode
import ke.co.kopilot.feature.camera.CameraPerformanceController
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.SpeedAlertEngine
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.sensors.RealTimeDrivingIntelligence
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.sensors.wearable.HeartRateRepository
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsCalculator
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsRepository
import ke.co.kopilot.feature.sensors.wearable.SmartwatchConnectionManager
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vision.RoadVisionPipeline
import ke.co.kopilot.feature.voice.VoiceToolExecutor
import ke.co.kopilot.feature.weather.WeatherRepository
import ke.co.kopilot.intelligence.VehicleIntelligenceEngine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale
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
    private val prefsRepo: PreferencesRepository,
    private val vehicleIntelligence: VehicleIntelligenceEngine,
    private val sensorManagerRepo: SensorManagerRepository,
    private val realTimeIntelligence: RealTimeDrivingIntelligence,
    private val cameraController: CameraPerformanceController,
    private val smartwatchConnectionManager: SmartwatchConnectionManager,
    private val heartRateRepository: HeartRateRepository,
    private val sleepAnalyticsRepository: SleepAnalyticsRepository,
) : VoiceToolExecutor {

    override suspend fun executeTool(name: String, arguments: JsonObject): String = when (name) {
        "get_current_location" -> {
            val locState = locationTracker.state.value
            val fix = locState.currentFix
            if (fix == null) {
                """{"status":"unavailable","reason":"GPS fix not available"}"""
            } else {
                buildJsonObject {
                    put("status", "ok")
                    put("latitude", "%.5f".format(Locale.US, fix.latitude))
                    put("longitude", "%.5f".format(Locale.US, fix.longitude))
                    put("accuracy_m", fix.horizontalAccuracyMeters?.toInt() ?: -1)
                    put("satellites", locState.satellitesUsedInFix)
                }.toString()
            }
        }
        "get_current_speed" -> {
            val vision = visionPipeline.state.value
            val eval = speedAlertEngine.evaluate(
                fix = locationTracker.state.value.currentFix,
                latestSign = vision.lastObservation,
                thresholdKmh = 5,
            )
            val obdSpeed = obdManager.liveTelemetry.value.vehicleSpeedKmh
            val effectiveSpeed = eval.currentSpeedKmh ?: obdSpeed?.toFloat()
            val observedLimit = vision.lastSpeedLimitKmh ?: eval.applicableSpeedLimitKmh
            buildJsonObject {
                put("status", eval.signalState.name)
                if (effectiveSpeed != null) put("speed_kmh", effectiveSpeed.toInt())
                if (observedLimit != null) put("observed_limit_kmh", observedLimit)
                put("last_sign", vision.lastObservation?.category?.displayName ?: "none")
                put("decision", eval.decision.name)
            }.toString()
        }
        "get_active_route" -> {
            val nav = navigationRepo.state.value
            val r = nav.activeRoute
            if (r == null) {
                """{"status":"unavailable","reason":"No active navigation route"}"""
            } else {
                buildJsonObject {
                    put("status", "ok")
                    put("destination", nav.destinationName ?: "Destination")
                    put("eta_minutes", r.durationSeconds / 60)
                    put("distance_km", "%.1f".format(Locale.US, r.distanceMeters / 1000.0))
                    put("next_instruction", r.steps.firstOrNull()?.instruction ?: "Continue")
                }.toString()
            }
        }
        "get_traffic_conditions" -> {
            val nav = navigationRepo.state.value
            val eval = trafficAdvisor.evaluate(nav.activeRoute, nav.alternativeRoutes)
            buildJsonObject {
                put("status", if (nav.activeRoute != null) "ok" else "unavailable")
                put("traffic_status", eval.statusLabel)
                put("delay_minutes", eval.delayMinutes)
            }.toString()
        }
        "find_alternative_routes" -> {
            val nav = navigationRepo.state.value
            val eval = trafficAdvisor.evaluate(nav.activeRoute, nav.alternativeRoutes)
            if (eval.recommendedAlternative == null) {
                """{"status":"ok","alternative_available":false,"reason":"No alternative route saves meaningful time"}"""
            } else {
                buildJsonObject {
                    put("status", "ok")
                    put("alternative_available", true)
                    put("summary", eval.recommendedAlternative?.summary ?: "Alternative route")
                    put("minutes_saved", eval.estimatedMinutesSaved)
                }.toString()
            }
        }
        "get_weather_forecast" -> {
            val w = weatherRepo.state.value
            if (!w.available) {
                """{"status":"unavailable"}"""
            } else {
                buildJsonObject {
                    put("status", "ok")
                    put("condition", w.conditionSummary)
                    w.temperatureC?.let { put("temperature_c", "%.0f".format(Locale.US, it)) }
                    w.rainProbabilityPercent?.let { put("rain_probability_percent", it) }
                    put("cached_offline", w.isStaleOfflineCache)
                }.toString()
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
                buildJsonObject {
                    put("status", "ok")
                    put("nearest_name", top.name)
                    put("address", top.formattedAddress)
                    put("count", results.size)
                }.toString()
            }
        }
        "get_recent_road_observations" -> {
            val v = visionPipeline.state.value
            val obs = v.lastObservation
            buildJsonObject {
                put("status", if (obs != null || v.lastSpeedLimitKmh != null) "ok" else "unavailable")
                put("last_sign", obs?.customLabel ?: obs?.category?.displayName ?: "none")
                put("sign", obs?.customLabel ?: obs?.category?.displayName ?: "none")
                v.lastSpeedLimitKmh?.let { put("last_speed_limit_kmh", it) }
                put("traffic_light", v.trafficLightSignal.name)
                v.lastDetectedPlate?.let { put("last_plate", it) }
                put("tracked_objects", v.trackedObjects.size)
            }.toString()
        }
        "get_smartwatch_health" -> {
            val diag = smartwatchConnectionManager.diagnostics.value
            val hr = heartRateRepository.state.value
            val summary = buildString {
                if (hr.isLiveReading && !hr.isStale && hr.currentBpm != null) {
                    append("Your current heart rate is ${hr.currentBpm} beats per minute")
                    if (hr.minBpm != null && hr.maxBpm != null && hr.avgBpm != null) {
                        append(", averaging ${hr.avgBpm}, with a range from ${hr.minBpm} to ${hr.maxBpm} BPM")
                    }
                    append(".")
                } else if (diag.connectedName != null) {
                    append("Your smartwatch ${diag.connectedName} status is ${diag.connectionState.displayName}, with no live heart rate reading right now.")
                } else {
                    append("No smartwatch is currently connected. Open Smartwatch and Health to connect your BLE watch.")
                }
                diag.batteryPercent?.let { append(" Watch battery is $it percent.") }
            }
            buildJsonObject {
                put("status", if (hr.isLiveReading && !hr.isStale) "ok" else "unavailable")
                put("summary", summary)
                hr.currentBpm?.let { put("current_bpm", it) }
                hr.avgBpm?.let { put("avg_bpm", it) }
                hr.minBpm?.let { put("min_bpm", it) }
                hr.maxBpm?.let { put("max_bpm", it) }
                diag.batteryPercent?.let { put("watch_battery_percent", it) }
                put("connection_state", diag.connectionState.displayName)
            }.toString()
        }
        "get_sleep_summary" -> {
            val sleep = sleepAnalyticsRepository.state.value
            val latest = sleep.latestSession
            val summary = if (latest == null) {
                "No synchronized sleep records are available yet. Open Smartwatch and Health to authorize Health Connect and sync your sleep."
            } else {
                val dur = SleepAnalyticsCalculator.formatDurationHoursMinutes(latest.totalSleepDurationMinutes)
                buildString {
                    append("Your last recorded sleep was $dur with ${latest.sleepEfficiencyPercent} percent efficiency from ${latest.sourceAppName}.")
                    if (latest.stagesAvailable) {
                        val deep = latest.deepSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m"
                        val rem = latest.remSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m"
                        val light = latest.lightSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m"
                        append(" Deep sleep $deep, light sleep $light, REM sleep $rem.")
                    }
                }
            }
            buildJsonObject {
                put("status", if (latest != null) "ok" else "unavailable")
                put("summary", summary)
            }.toString()
        }
        "get_all_sensors_summary" -> {
            val hub = sensorManagerRepo.state.value
            val motion = hub.motionAnalysis
            val stats = motion.behaviourStats
            val compass = hub.compassReading
            val intel = realTimeIntelligence.state.value
            val cam = cameraController.status.value
            val fix = locationTracker.state.value.currentFix
            val obd = obdManager.state.value
            val tel = obdManager.liveTelemetry.value
            val hr = heartRateRepository.state.value

            val summary = buildString {
                val speedKmh = fix?.speedKmh?.toInt() ?: tel.vehicleSpeedKmh
                val fullCompassDir = ke.co.kopilot.feature.sensors.CompassHeadingEstimator.toFullDirectionName(compass.cardinal)
                append("Heading $fullCompassDir at ${compass.degrees} degrees. ")
                if (speedKmh != null) append("Speed $speedKmh kilometres per hour. ")
                append("Road surface score is ${stats.averageRoadScore} out of 100 with ${stats.potholeBumpCount} bumps detected. ")
                append("Driving safety score is ${intel.safetyScore}, fuel efficiency score is ${intel.fuelEfficiencyScore}. ")
                if (obd.obdConnected) {
                    tel.engineRpm?.let { append("Engine at $it RPM. ") }
                    tel.coolantTempC?.let { append("Coolant $it degrees Celsius. ") }
                    (tel.controlModuleVoltageV ?: tel.adapterVoltageV)?.let {
                        append("Battery voltage ${"%.1f".format(Locale.US, it)} volts. ")
                    }
                } else {
                    append("OBD adapter is offline. ")
                }
                if (hr.isLiveReading && !hr.isStale && hr.currentBpm != null) {
                    append("Heart rate ${hr.currentBpm} BPM. ")
                }
                append("Phone thermal state is ${cam.thermalState.label}")
                cam.batteryTemperatureC?.let { append(" at ${"%.1f".format(Locale.US, it)} degrees Celsius") }
                append(".")
            }
            buildJsonObject {
                put("status", "ok")
                put("summary", summary)
                put("compass_cardinal", compass.cardinal)
                put("compass_degrees", compass.degrees)
                put("road_score", stats.averageRoadScore)
                put("bumps_count", stats.potholeBumpCount)
                put("safety_score", intel.safetyScore)
                put("fuel_score", intel.fuelEfficiencyScore)
                put("thermal_state", cam.thermalState.label)
                put("satellites", locationTracker.state.value.satellitesUsedInFix)
            }.toString()
        }
        "set_voice_mode" -> {
            val raw = arguments["mode"]?.jsonPrimitive?.content?.uppercase() ?: "SMART_CO_DRIVER"
            val mode = runCatching { VoiceMode.valueOf(raw) }.getOrDefault(VoiceMode.SMART_CO_DRIVER)
            prefsRepo.setVoiceMode(mode)
            """{"status":"ok","voice_mode":"${mode.name}"}"""
        }
        "voice_configure_setting" -> {
            val command = arguments["command"]?.jsonPrimitive?.content?.lowercase().orEmpty()
            when {
                command.contains("add plate") || command.contains("watch plate") -> {
                    val plate = command.substringAfter("plate").trim().uppercase()
                    if (plate.length >= 4) {
                        prefsRepo.addWatchedPlate(plate)
                        """{"status":"ok","message":"Added plate $plate to your watchlist."}"""
                    } else {
                        """{"status":"unavailable","message":"Please say the full number plate after add plate."}"""
                    }
                }
                command.contains("unmute") -> {
                    prefsRepo.setAlertsMuted(false)
                    """{"status":"ok","message":"Alerts unmuted."}"""
                }
                command.contains("mute") -> {
                    prefsRepo.setAlertsMuted(true)
                    """{"status":"ok","message":"Alerts muted."}"""
                }
                command.contains("feminine") -> {
                    prefsRepo.setAiVoiceName("Feminine")
                    """{"status":"ok","message":"Switched voice to Feminine."}"""
                }
                command.contains("masculine") -> {
                    prefsRepo.setAiVoiceName("Masculine")
                    """{"status":"ok","message":"Switched voice to Masculine."}"""
                }
                command.contains("futuristic") -> {
                    prefsRepo.setAiVoiceName("Futuristic")
                    """{"status":"ok","message":"Switched voice to Futuristic."}"""
                }
                else -> """{"status":"unavailable","message":"Setting command not recognized."}"""
            }
        }
        "get_vehicle_diagnostics" -> {
            val obd = obdManager.state.value
            val tel = obdManager.liveTelemetry.value
            val conn = obdManager.connectionState.value
            if (!obd.obdConnected) {
                """{"status":"unavailable","connection_state":"${conn.name}","reason":"${obd.statusMessage}"}"""
            } else {
                buildJsonObject {
                    put("status", "ok")
                    put("connection_state", conn.name)
                    put("adapter", tel.adapterInfo ?: obd.adapterName ?: "OBD-II")
                    put("protocol", tel.protocolName ?: obd.protocolName ?: "Auto")
                    tel.engineRpm?.let { put("rpm", it) }
                    tel.vehicleSpeedKmh?.let { put("obd_speed_kmh", it) }
                    tel.coolantTempC?.let { put("coolant_c", it) }
                    tel.engineLoadPct?.let { put("engine_load_pct", it) }
                    tel.throttlePositionPct?.let { put("throttle_pct", it) }
                    (tel.controlModuleVoltageV ?: tel.adapterVoltageV)?.let { put("voltage_v", it) }
                    tel.mafGramsPerSec?.let { put("maf_gps", it) }
                    tel.intakeAirTempC?.let { put("intake_c", it) }
                    tel.fuelLevelPct?.let { put("fuel_level_pct", it) }
                    put("dtc_count", obd.dtcCodes.size)
                    put("transmission_dtc_count", tel.transmissionDtcCodes.size)
                    put("transmission_note", tel.transmissionStatusNote)
                }.toString()
            }
        }
        "get_trip_summary" -> {
            val distKm = locationTracker.state.value.distanceTraveledMeters / 1000.0
            val savedCount = tripRepo.trips.firstOrNull()?.size ?: 0
            buildJsonObject {
                put("status", "ok")
                put("current_trip_distance_km", "%.2f".format(Locale.US, distKm))
                put("saved_trips_count", savedCount)
            }.toString()
        }
        "ask_vehicle_intelligence" -> {
            val question = arguments["question"]?.jsonPrimitive?.content ?: "How is my car performing right now?"
            val (answer, _) = vehicleIntelligence.answerVehicleQuestion(question)
            buildJsonObject {
                put("status", "ok")
                put("answer", answer)
            }.toString()
        }
        "get_vehicle_snapshot" -> {
            val snap = vehicleIntelligence.getVehicleSnapshot()
            buildJsonObject {
                put("status", "ok")
                put("vehicle", snap.vehicleIdentity)
                put("obd_connected", snap.obdConnected)
                put("available_pids", snap.availableCount())
                put("unsupported_pids", snap.unsupportedCount())
                put("dtc_scan_status", snap.diagnostics.scanStatus.name)
            }.toString()
        }
        "get_full_vehicle_health_report" -> {
            val report = vehicleIntelligence.generateFullVehicleHealthReport(performFreshDtcScan = true)
            buildJsonObject {
                put("status", "ok")
                put("spoken_summary", report.spokenSummary)
                put("detailed_report", report.detailedText)
            }.toString()
        }
        "get_supported_sensors" -> {
            val sup = vehicleIntelligence.getSupportedSensors()
            buildJsonObject {
                put("status", "ok")
                put("available", sup["available"].orEmpty().joinToString(", "))
                put("unsupported", sup["unsupported"].orEmpty().joinToString(", "))
            }.toString()
        }
        "get_diagnostic_codes" -> {
            val dtcs = vehicleIntelligence.getDiagnosticCodes()
            buildJsonObject {
                put("status", "ok")
                put("count", dtcs.size)
                put("codes", dtcs.joinToString("; ") { "${it.code} (${it.severityLabel}): ${it.description}" })
            }.toString()
        }
        "get_fuel_efficiency_summary" -> {
            buildJsonObject {
                put("status", "ok")
                put("summary", vehicleIntelligence.getFuelEfficiencySummary())
            }.toString()
        }
        "get_driving_safety_summary" -> {
            buildJsonObject {
                put("status", "ok")
                put("summary", vehicleIntelligence.getDrivingSafetySummary())
            }.toString()
        }
        "get_vehicle_health_alerts" -> {
            val alerts = vehicleIntelligence.getVehicleHealthAlerts()
            buildJsonObject {
                put("status", "ok")
                put("count", alerts.size)
                put("alerts", alerts.joinToString("; "))
            }.toString()
        }
        else -> """{"status":"unavailable","reason":"Unknown tool $name"}"""
    }
}
