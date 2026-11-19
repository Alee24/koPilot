package ke.co.kopilot.core.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kopilot_settings")

enum class DetectionQuality { FAST, BALANCED, HIGH }
enum class MicMode { HANDS_FREE, PUSH_TO_TALK }
enum class ResponseLength { CONCISE, STANDARD }
enum class VoiceMode { QUIET, SMART_CO_DRIVER, LEARNING }

data class KoPilotSettings(
    // General
    val language: String = "en",
    val darkTheme: Boolean = true,
    val distanceUnit: String = "km",
    val voiceVolume: Float = 0.9f,
    val voiceSpeed: Float = 1.0f,
    val speedAlertThresholdKmh: Int = 5,
    val alertsMuted: Boolean = false,
    val safetyDisclosureAccepted: Boolean = false,
    // Camera
    val cameraPrimaryLayout: Boolean = true,
    val detectionQuality: DetectionQuality = DetectionQuality.BALANCED,
    val cameraZoomRatio: Float = 1.3f,
    val hazardAdvisoriesEnabled: Boolean = false, // Disabled by default until validated
    val announceAllSigns: Boolean = true,
    // Navigation
    val avoidTolls: Boolean = false,
    val trafficAlertsEnabled: Boolean = true,
    val navVoiceGuidanceEnabled: Boolean = true,
    // AI Assistant
    val voiceMode: VoiceMode = VoiceMode.SMART_CO_DRIVER,
    val aiVoiceName: String = "marin",
    val alertChimeEnabled: Boolean = true,
    val micMode: MicMode = MicMode.HANDS_FREE,
    val responseLength: ResponseLength = ResponseLength.CONCISE,
    // Emergency Contact
    val emergencyContactName: String = "Kenya Emergency (999)",
    val emergencyContactPhone: String = "999",
    // Alert Preferences
    val alertRoadSignsEnabled: Boolean = true,
    val alertLaneTurnEnabled: Boolean = true,
    val alertTrafficLightsEnabled: Boolean = true,
    val alertSpeedLimitEnabled: Boolean = true,
    val alertWeatherEnabled: Boolean = true,
    val alertNumberPlatesEnabled: Boolean = true,
    val alertRoadQualityEnabled: Boolean = true,
    val watchedPlatesCsv: String = "",
    // Sensors (activated only when Start Driving is pressed)
    val sensorCameraEnabled: Boolean = true,
    val sensorGpsEnabled: Boolean = true,
    val sensorMotionEnabled: Boolean = true,
    val sensorMicEnabled: Boolean = true,
    val sensorObdEnabled: Boolean = true,
    // Privacy
    val tripHistoryEnabled: Boolean = true,
    val dataRetentionDays: Int = 90,
    val diagnosticLoggingEnabled: Boolean = false,
    val telemetryOptIn: Boolean = false,
) {
    fun watchedPlatesList(): List<String> =
        watchedPlatesCsv.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settingsFlow: Flow<KoPilotSettings> = context.dataStore.data.map { prefs ->
        KoPilotSettings(
            language = prefs[KEY_LANGUAGE] ?: "en",
            darkTheme = prefs[KEY_DARK_THEME] ?: true,
            distanceUnit = prefs[KEY_DISTANCE_UNIT] ?: "km",
            voiceVolume = prefs[KEY_VOICE_VOLUME] ?: 0.9f,
            voiceSpeed = prefs[KEY_VOICE_SPEED] ?: 1.0f,
            speedAlertThresholdKmh = prefs[KEY_SPEED_THRESHOLD] ?: 5,
            alertsMuted = prefs[KEY_ALERTS_MUTED] ?: false,
            safetyDisclosureAccepted = prefs[KEY_SAFETY_ACCEPTED] ?: false,
            cameraPrimaryLayout = prefs[KEY_CAMERA_PRIMARY] ?: true,
            detectionQuality = runCatching {
                DetectionQuality.valueOf(prefs[KEY_DETECTION_QUALITY] ?: "BALANCED")
            }.getOrDefault(DetectionQuality.BALANCED),
            cameraZoomRatio = prefs[KEY_CAMERA_ZOOM] ?: 1.3f,
            hazardAdvisoriesEnabled = prefs[KEY_HAZARD_ADVISORIES] ?: false,
            announceAllSigns = prefs[KEY_ANNOUNCE_ALL_SIGNS] ?: true,
            avoidTolls = prefs[KEY_AVOID_TOLLS] ?: false,
            trafficAlertsEnabled = prefs[KEY_TRAFFIC_ALERTS] ?: true,
            navVoiceGuidanceEnabled = prefs[KEY_NAV_VOICE] ?: true,
            voiceMode = runCatching {
                VoiceMode.valueOf(prefs[KEY_VOICE_MODE] ?: "SMART_CO_DRIVER")
            }.getOrDefault(VoiceMode.SMART_CO_DRIVER),
            aiVoiceName = prefs[KEY_AI_VOICE] ?: "marin",
            alertChimeEnabled = prefs[KEY_ALERT_CHIME] ?: true,
            micMode = runCatching {
                MicMode.valueOf(prefs[KEY_MIC_MODE] ?: "HANDS_FREE")
            }.getOrDefault(MicMode.HANDS_FREE),
            responseLength = runCatching {
                ResponseLength.valueOf(prefs[KEY_RESPONSE_LENGTH] ?: "CONCISE")
            }.getOrDefault(ResponseLength.CONCISE),
            emergencyContactName = prefs[KEY_EMERGENCY_NAME] ?: "Kenya Emergency (999)",
            emergencyContactPhone = prefs[KEY_EMERGENCY_PHONE] ?: "999",
            alertRoadSignsEnabled = prefs[KEY_ALERT_ROAD_SIGNS] ?: true,
            alertLaneTurnEnabled = prefs[KEY_ALERT_LANE_TURN] ?: true,
            alertTrafficLightsEnabled = prefs[KEY_ALERT_TRAFFIC_LIGHTS] ?: true,
            alertSpeedLimitEnabled = prefs[KEY_ALERT_SPEED_LIMIT] ?: true,
            alertWeatherEnabled = prefs[KEY_ALERT_WEATHER] ?: true,
            alertNumberPlatesEnabled = prefs[KEY_ALERT_NUMBER_PLATES] ?: true,
            alertRoadQualityEnabled = prefs[KEY_ALERT_ROAD_QUALITY] ?: true,
            watchedPlatesCsv = prefs[KEY_WATCHED_PLATES] ?: "",
            sensorCameraEnabled = prefs[KEY_SENSOR_CAMERA] ?: true,
            sensorGpsEnabled = prefs[KEY_SENSOR_GPS] ?: true,
            sensorMotionEnabled = prefs[KEY_SENSOR_MOTION] ?: true,
            sensorMicEnabled = prefs[KEY_SENSOR_MIC] ?: true,
            sensorObdEnabled = prefs[KEY_SENSOR_OBD] ?: true,
            tripHistoryEnabled = prefs[KEY_TRIP_HISTORY] ?: true,
            dataRetentionDays = prefs[KEY_RETENTION_DAYS] ?: 90,
            diagnosticLoggingEnabled = prefs[KEY_DIAGNOSTIC_LOGGING] ?: false,
            telemetryOptIn = prefs[KEY_TELEMETRY_OPT_IN] ?: false,
        )
    }

    suspend fun update(transform: (MutableMap<String, Any?>) -> Unit) = Unit

    suspend fun setLanguage(language: String) = edit { it[KEY_LANGUAGE] = language }
    suspend fun setDarkTheme(dark: Boolean) = edit { it[KEY_DARK_THEME] = dark }
    suspend fun setDistanceUnit(unit: String) = edit { it[KEY_DISTANCE_UNIT] = unit }
    suspend fun setVoiceVolume(volume: Float) = edit { it[KEY_VOICE_VOLUME] = volume.coerceIn(0f, 1f) }
    suspend fun setVoiceSpeed(speed: Float) = edit { it[KEY_VOICE_SPEED] = speed.coerceIn(0.6f, 1.5f) }
    suspend fun setSpeedAlertThresholdKmh(kmh: Int) = edit { it[KEY_SPEED_THRESHOLD] = kmh.coerceIn(0, 40) }
    suspend fun setAlertsMuted(muted: Boolean) = edit { it[KEY_ALERTS_MUTED] = muted }
    suspend fun setSafetyDisclosureAccepted(accepted: Boolean) = edit { it[KEY_SAFETY_ACCEPTED] = accepted }
    suspend fun setCameraPrimaryLayout(cameraPrimary: Boolean) = edit { it[KEY_CAMERA_PRIMARY] = cameraPrimary }
    suspend fun setDetectionQuality(quality: DetectionQuality) = edit { it[KEY_DETECTION_QUALITY] = quality.name }
    suspend fun setCameraZoomRatio(zoom: Float) = edit { it[KEY_CAMERA_ZOOM] = zoom.coerceIn(1.0f, 3.0f) }
    suspend fun setHazardAdvisoriesEnabled(enabled: Boolean) = edit { it[KEY_HAZARD_ADVISORIES] = enabled }
    suspend fun setAnnounceAllSigns(enabled: Boolean) = edit { it[KEY_ANNOUNCE_ALL_SIGNS] = enabled }
    suspend fun setAvoidTolls(avoid: Boolean) = edit { it[KEY_AVOID_TOLLS] = avoid }
    suspend fun setTrafficAlertsEnabled(enabled: Boolean) = edit { it[KEY_TRAFFIC_ALERTS] = enabled }
    suspend fun setNavVoiceGuidanceEnabled(enabled: Boolean) = edit { it[KEY_NAV_VOICE] = enabled }
    suspend fun setVoiceMode(mode: VoiceMode) = edit { it[KEY_VOICE_MODE] = mode.name }
    suspend fun setAiVoiceName(voice: String) = edit { it[KEY_AI_VOICE] = voice }
    suspend fun setAlertChimeEnabled(enabled: Boolean) = edit { it[KEY_ALERT_CHIME] = enabled }
    suspend fun setMicMode(mode: MicMode) = edit { it[KEY_MIC_MODE] = mode.name }
    suspend fun setResponseLength(length: ResponseLength) = edit { it[KEY_RESPONSE_LENGTH] = length.name }
    suspend fun setEmergencyContact(name: String, phone: String) = edit {
        it[KEY_EMERGENCY_NAME] = name
        it[KEY_EMERGENCY_PHONE] = phone
    }
    suspend fun setAlertRoadSignsEnabled(enabled: Boolean) = edit { it[KEY_ALERT_ROAD_SIGNS] = enabled }
    suspend fun setAlertLaneTurnEnabled(enabled: Boolean) = edit { it[KEY_ALERT_LANE_TURN] = enabled }
    suspend fun setAlertTrafficLightsEnabled(enabled: Boolean) = edit { it[KEY_ALERT_TRAFFIC_LIGHTS] = enabled }
    suspend fun setAlertSpeedLimitEnabled(enabled: Boolean) = edit { it[KEY_ALERT_SPEED_LIMIT] = enabled }
    suspend fun setAlertWeatherEnabled(enabled: Boolean) = edit { it[KEY_ALERT_WEATHER] = enabled }
    suspend fun setSensorCameraEnabled(enabled: Boolean) = edit { it[KEY_SENSOR_CAMERA] = enabled }
    suspend fun setSensorGpsEnabled(enabled: Boolean) = edit { it[KEY_SENSOR_GPS] = enabled }
    suspend fun setSensorMotionEnabled(enabled: Boolean) = edit { it[KEY_SENSOR_MOTION] = enabled }
    suspend fun setSensorMicEnabled(enabled: Boolean) = edit { it[KEY_SENSOR_MIC] = enabled }
    suspend fun setSensorObdEnabled(enabled: Boolean) = edit { it[KEY_SENSOR_OBD] = enabled }
    suspend fun setAllSensorsEnabled(enabled: Boolean) = edit {
        it[KEY_SENSOR_CAMERA] = enabled
        it[KEY_SENSOR_GPS] = enabled
        it[KEY_SENSOR_MOTION] = enabled
        it[KEY_SENSOR_MIC] = enabled
        it[KEY_SENSOR_OBD] = enabled
    }
    suspend fun setTripHistoryEnabled(enabled: Boolean) = edit { it[KEY_TRIP_HISTORY] = enabled }
    suspend fun setAlertNumberPlatesEnabled(enabled: Boolean) = edit { it[KEY_ALERT_NUMBER_PLATES] = enabled }
    suspend fun setAlertRoadQualityEnabled(enabled: Boolean) = edit { it[KEY_ALERT_ROAD_QUALITY] = enabled }
    suspend fun addWatchedPlate(rawPlate: String) = edit { prefs ->
        val clean = rawPlate.uppercase().replace(Regex("[^A-Z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.length < 4) return@edit
        val existing = (prefs[KEY_WATCHED_PLATES] ?: "").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
        existing.add(clean)
        prefs[KEY_WATCHED_PLATES] = existing.joinToString(",")
    }
    suspend fun removeWatchedPlate(rawPlate: String) = edit { prefs ->
        val target = rawPlate.trim().uppercase()
        val updated = (prefs[KEY_WATCHED_PLATES] ?: "").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() && it != target }
        prefs[KEY_WATCHED_PLATES] = updated.joinToString(",")
    }
    suspend fun setDataRetentionDays(days: Int) = edit { it[KEY_RETENTION_DAYS] = days.coerceIn(7, 365) }
    suspend fun setDiagnosticLoggingEnabled(enabled: Boolean) = edit { it[KEY_DIAGNOSTIC_LOGGING] = enabled }
    suspend fun setTelemetryOptIn(optIn: Boolean) = edit { it[KEY_TELEMETRY_OPT_IN] = optIn }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        val KEY_DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val KEY_VOICE_VOLUME = floatPreferencesKey("voice_volume")
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_SPEED_THRESHOLD = intPreferencesKey("speed_threshold")
        val KEY_ALERTS_MUTED = booleanPreferencesKey("alerts_muted")
        val KEY_SAFETY_ACCEPTED = booleanPreferencesKey("safety_accepted")
        val KEY_CAMERA_PRIMARY = booleanPreferencesKey("camera_primary")
        val KEY_DETECTION_QUALITY = stringPreferencesKey("detection_quality")
        val KEY_CAMERA_ZOOM = floatPreferencesKey("camera_zoom")
        val KEY_HAZARD_ADVISORIES = booleanPreferencesKey("hazard_advisories")
        val KEY_ANNOUNCE_ALL_SIGNS = booleanPreferencesKey("announce_all_signs")
        val KEY_AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val KEY_TRAFFIC_ALERTS = booleanPreferencesKey("traffic_alerts")
        val KEY_NAV_VOICE = booleanPreferencesKey("nav_voice")
        val KEY_VOICE_MODE = stringPreferencesKey("voice_mode")
        val KEY_AI_VOICE = stringPreferencesKey("ai_voice")
        val KEY_ALERT_CHIME = booleanPreferencesKey("alert_chime")
        val KEY_MIC_MODE = stringPreferencesKey("mic_mode")
        val KEY_RESPONSE_LENGTH = stringPreferencesKey("response_length")
        val KEY_EMERGENCY_NAME = stringPreferencesKey("emergency_name")
        val KEY_EMERGENCY_PHONE = stringPreferencesKey("emergency_phone")
        val KEY_ALERT_ROAD_SIGNS = booleanPreferencesKey("alert_road_signs")
        val KEY_ALERT_LANE_TURN = booleanPreferencesKey("alert_lane_turn")
        val KEY_ALERT_TRAFFIC_LIGHTS = booleanPreferencesKey("alert_traffic_lights")
        val KEY_ALERT_SPEED_LIMIT = booleanPreferencesKey("alert_speed_limit")
        val KEY_ALERT_WEATHER = booleanPreferencesKey("alert_weather")
        val KEY_ALERT_NUMBER_PLATES = booleanPreferencesKey("alert_number_plates")
        val KEY_ALERT_ROAD_QUALITY = booleanPreferencesKey("alert_road_quality")
        val KEY_WATCHED_PLATES = stringPreferencesKey("watched_plates")
        val KEY_SENSOR_CAMERA = booleanPreferencesKey("sensor_camera")
        val KEY_SENSOR_GPS = booleanPreferencesKey("sensor_gps")
        val KEY_SENSOR_MOTION = booleanPreferencesKey("sensor_motion")
        val KEY_SENSOR_MIC = booleanPreferencesKey("sensor_mic")
        val KEY_SENSOR_OBD = booleanPreferencesKey("sensor_obd")
        val KEY_TRIP_HISTORY = booleanPreferencesKey("trip_history")
        val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
        val KEY_DIAGNOSTIC_LOGGING = booleanPreferencesKey("diagnostic_logging")
        val KEY_TELEMETRY_OPT_IN = booleanPreferencesKey("telemetry_opt_in")
    }
}
