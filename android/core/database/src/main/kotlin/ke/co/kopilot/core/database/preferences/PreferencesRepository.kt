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
    val aiVoiceName: String = "marin",
    val alertChimeEnabled: Boolean = true,
    val micMode: MicMode = MicMode.HANDS_FREE,
    val responseLength: ResponseLength = ResponseLength.CONCISE,
    // Emergency Contact
    val emergencyContactName: String = "Kenya Emergency (999)",
    val emergencyContactPhone: String = "999",
    // Privacy
    val tripHistoryEnabled: Boolean = true,
    val dataRetentionDays: Int = 90,
    val diagnosticLoggingEnabled: Boolean = false,
    val telemetryOptIn: Boolean = false,
)

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
    suspend fun setAiVoiceName(voice: String) = edit { it[KEY_AI_VOICE] = voice }
    suspend fun setAlertChimeEnabled(enabled: Boolean) = edit { it[KEY_ALERT_CHIME] = enabled }
    suspend fun setMicMode(mode: MicMode) = edit { it[KEY_MIC_MODE] = mode.name }
    suspend fun setResponseLength(length: ResponseLength) = edit { it[KEY_RESPONSE_LENGTH] = length.name }
    suspend fun setEmergencyContact(name: String, phone: String) = edit {
        it[KEY_EMERGENCY_NAME] = name
        it[KEY_EMERGENCY_PHONE] = phone
    }
    suspend fun setTripHistoryEnabled(enabled: Boolean) = edit { it[KEY_TRIP_HISTORY] = enabled }
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
        val KEY_AI_VOICE = stringPreferencesKey("ai_voice")
        val KEY_ALERT_CHIME = booleanPreferencesKey("alert_chime")
        val KEY_MIC_MODE = stringPreferencesKey("mic_mode")
        val KEY_RESPONSE_LENGTH = stringPreferencesKey("response_length")
        val KEY_EMERGENCY_NAME = stringPreferencesKey("emergency_name")
        val KEY_EMERGENCY_PHONE = stringPreferencesKey("emergency_phone")
        val KEY_TRIP_HISTORY = booleanPreferencesKey("trip_history")
        val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
        val KEY_DIAGNOSTIC_LOGGING = booleanPreferencesKey("diagnostic_logging")
        val KEY_TELEMETRY_OPT_IN = booleanPreferencesKey("telemetry_opt_in")
    }
}
