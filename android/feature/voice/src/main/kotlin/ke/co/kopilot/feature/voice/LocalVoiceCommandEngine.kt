package ke.co.kopilot.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.security.SecureTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 100% Offline Hands-Free Local Voice Command Engine.
 *
 * - Runs continuously while active (auto-restarts on silence/timeout without requiring re-taps).
 * - Pauses recognition while KoPilot speaks its response, then automatically resumes listening.
 * - Uses Android on-device `SpeechRecognizer` (`EXTRA_PREFER_OFFLINE = true`) with zero internet requirement.
 * - Routes all smartwatch, heart rate, sleep, OBD-II, fuel consumption, voltage, engine, sensor,
 *   road quality, compass, thermal, traffic-light, number-plate, and road-sign questions offline.
 */
@Singleton
class LocalVoiceCommandEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolExecutor: VoiceToolExecutor,
    private val tokenStore: SecureTokenStore,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val json = Json { ignoreUnknownKeys = true }

    private var recognizer: SpeechRecognizer? = null
    private val continuousActive = AtomicBoolean(false)
    private var activeLanguage: String = "en"
    private var listeningCallback: ((Boolean) -> Unit)? = null
    private var resultCallback: ((String, String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var pendingRestartRunnable: Runnable? = null
    private var consecutiveFatalErrors = 0

    fun startListening(
        language: String,
        continuous: Boolean = true,
        onListeningChanged: (Boolean) -> Unit,
        onResultSpoken: (transcript: String, responseText: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        activeLanguage = language
        listeningCallback = onListeningChanged
        resultCallback = onResultSpoken
        errorCallback = onError
        continuousActive.set(continuous)
        consecutiveFatalErrors = 0

        startSingleRecognitionSession()
    }

    /**
     * Resumes continuous hands-free listening after KoPilot finishes speaking a reply.
     */
    fun resumeAfterSpeech(delayMs: Long = 900L) {
        if (!continuousActive.get()) return
        scheduleRestart(delayMs)
    }

    fun stop() {
        continuousActive.set(false)
        mainHandler.post {
            pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRestartRunnable = null
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            listeningCallback?.invoke(false)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.post {
            pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            if (!continuousActive.get()) return@post
            val r = Runnable {
                if (continuousActive.get()) {
                    startSingleRecognitionSession()
                }
            }
            pendingRestartRunnable = r
            mainHandler.postDelayed(r, delayMs)
        }
    }

    private fun startSingleRecognitionSession() {
        mainHandler.post {
            pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRestartRunnable = null

            val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else {
                false
            }
            val standardAvailable = SpeechRecognizer.isRecognitionAvailable(context)

            if (!onDeviceAvailable && !standardAvailable) {
                continuousActive.set(false)
                errorCallback?.invoke("On-device speech recognition service is not installed on this phone.")
                return@post
            }

            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }

            val sr = if (onDeviceAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                    .getOrElse { SpeechRecognizer.createSpeechRecognizer(context) }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    consecutiveFatalErrors = 0
                    listeningCallback?.invoke(true)
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    listeningCallback?.invoke(false)
                }

                override fun onError(error: Int) {
                    listeningCallback?.invoke(false)
                    if (!continuousActive.get()) return

                    when (error) {
                        // Normal silence/timeout in hands-free driving mode -> silently re-arm microphone
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            consecutiveFatalErrors = 0
                            scheduleRestart(600L)
                        }

                        // Temporary busy/audio error -> short backoff retry
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_CLIENT -> {
                            consecutiveFatalErrors += 1
                            if (consecutiveFatalErrors <= 6) {
                                scheduleRestart(1_200L)
                            } else {
                                continuousActive.set(false)
                                errorCallback?.invoke("Microphone recognizer busy (code $error). Tap Voice AI to restart.")
                            }
                        }

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            continuousActive.set(false)
                            errorCallback?.invoke("Microphone permission denied. Grant RECORD_AUDIO permission.")
                        }

                        else -> {
                            // Network/server error on devices without downloaded offline pack -> retry cleanly
                            consecutiveFatalErrors += 1
                            if (consecutiveFatalErrors <= 4) {
                                scheduleRestart(1_800L)
                            } else {
                                continuousActive.set(false)
                                errorCallback?.invoke("Offline speech pack unavailable (code $error). Tap Voice AI to retry.")
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    listeningCallback?.invoke(false)
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()

                    if (spoken.isBlank()) {
                        if (continuousActive.get()) scheduleRestart(600L)
                        return
                    }

                    scope.launch {
                        val reply = handleSpokenCommand(spoken, activeLanguage)
                        resultCallback?.invoke(spoken, reply)
                        // Estimate spoken reply duration so mic doesn't capture KoPilot's own TTS output
                        val estimatedSpeechMs = (reply.length * 68L).coerceIn(2_000L, 9_500L)
                        if (continuousActive.get()) {
                            scheduleRestart(estimatedSpeechMs)
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (activeLanguage == "sw") "sw-KE" else "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            runCatching {
                sr.startListening(intent)
            }.onFailure { err ->
                listeningCallback?.invoke(false)
                if (continuousActive.get()) {
                    scheduleRestart(1_500L)
                } else {
                    errorCallback?.invoke("Could not start microphone: ${err.localizedMessage ?: "error"}")
                }
            }
        }
    }

    internal suspend fun handleSpokenCommand(utterance: String, language: String): String {
        val q = utterance.lowercase().trim()
        return when {
            // 1. Voice Mode & App Settings
            q.contains("quiet mode") || q.contains("warnings only") -> {
                toolExecutor.executeTool("set_voice_mode", buildJsonObject { put("mode", "QUIET") })
                "Switched to Quiet Mode. Only critical safety warnings will be announced."
            }
            q.contains("learning mode") -> {
                toolExecutor.executeTool("set_voice_mode", buildJsonObject { put("mode", "LEARNING") })
                "Switched to Learning Mode. I will explain each newly spotted road sign once."
            }
            q.contains("smart mode") || q.contains("co-driver") || q.contains("codriver") || q.contains("co driver") -> {
                toolExecutor.executeTool("set_voice_mode", buildJsonObject { put("mode", "SMART_CO_DRIVER") })
                "Switched to Smart Co-Driver Mode."
            }
            q.contains("add plate") || q.contains("watch plate") || q.contains("mute alert") || q.contains("unmute alert") || q.contains("set voice") || q.contains("change voice") -> {
                val args = buildJsonObject { put("command", utterance) }
                val res = parseObj(toolExecutor.executeTool("voice_configure_setting", args))
                res["message"]?.jsonPrimitive?.content ?: "Updated setting."
            }

            // 2. Smartwatch, Heartbeat, Pulse & BPM (100% Offline)
            q.contains("heartbeat") ||
                q.contains("heart beat") ||
                q.contains("heart rate") ||
                q.contains("pulse") ||
                q.contains("bpm") ||
                q.contains("smartwatch") ||
                q.contains("smart watch") ||
                q.contains("watch battery") ||
                q.contains("my watch") ||
                q.contains("mapigo ya moyo") -> {
                val res = parseObj(toolExecutor.executeTool("get_smartwatch_health", emptyObj()))
                res["summary"]?.jsonPrimitive?.content ?: "Smartwatch heart rate is currently unavailable."
            }

            // 3. Sleep Analytics (100% Offline Local Cache / Health Connect)
            q.contains("sleep") || q.contains("usingizi") || q.contains("how did i rest") -> {
                val res = parseObj(toolExecutor.executeTool("get_sleep_summary", emptyObj()))
                res["summary"]?.jsonPrimitive?.content ?: "No sleep records are available yet."
            }

            // 4. Fuel Consumption, Fuel Economy, Fuel Level & OBD-II Vehicle Intelligence (100% Offline)
            q.contains("fuel consumption") ||
                q.contains("fuel economy") ||
                q.contains("fuel efficiency") ||
                q.contains("fuel rate") ||
                q.contains("fuel level") ||
                q.contains("how much fuel") ||
                q.contains("check my fuel") ||
                q.contains("my fuel") ||
                q.contains("mafuta") -> {
                val res = parseObj(toolExecutor.executeTool("get_fuel_efficiency_summary", emptyObj()))
                res["summary"]?.jsonPrimitive?.content
                    ?: parseObj(toolExecutor.executeTool("ask_vehicle_intelligence", buildJsonObject { put("question", utterance) }))["answer"]?.jsonPrimitive?.content
                    ?: "Fuel consumption data requires an active OBD-II connection."
            }

            // 5. Voltage, Battery, Engine, Coolant, RPM, Throttle, MAF, DTCs & OBD-II Diagnostics (100% Offline)
            q.contains("voltage") ||
                q.contains("volts") ||
                q.contains("battery") ||
                q.contains("coolant") ||
                q.contains("overheat") ||
                q.contains("rpm") ||
                q.contains("engine") ||
                q.contains("throttle") ||
                q.contains("maf") ||
                q.contains("air flow") ||
                q.contains("airflow") ||
                q.contains("intake") ||
                q.contains("fault") ||
                q.contains("dtc") ||
                q.contains("error code") ||
                q.contains("warning light") ||
                q.contains("diagnostic") ||
                q.contains("obd") ||
                q.contains("my car") ||
                q.contains("vehicle health") ||
                q.contains("health report") ||
                q.contains("check before driving") -> {
                val args = buildJsonObject { put("question", utterance) }
                val res = parseObj(toolExecutor.executeTool("ask_vehicle_intelligence", args))
                res["answer"]?.jsonPrimitive?.content ?: "Vehicle OBD diagnostics are currently unavailable."
            }

            // 6. All Phone Sensors, Compass, Road Surface, Thermal & Full Status Check (100% Offline)
            q.contains("all sensors") ||
                q.contains("read sensors") ||
                q.contains("check sensors") ||
                q.contains("sensor") ||
                q.contains("compass") ||
                q.contains("heading") ||
                q.contains("direction") ||
                q.contains("road quality") ||
                q.contains("road surface") ||
                q.contains("bumps") ||
                q.contains("pothole") ||
                q.contains("safety score") ||
                q.contains("thermal") ||
                q.contains("phone temperature") ||
                q.contains("status report") ||
                q.contains("check everything") ||
                q.contains("how am i driving") -> {
                val res = parseObj(toolExecutor.executeTool("get_all_sensors_summary", emptyObj()))
                res["summary"]?.jsonPrimitive?.content ?: "Sensor summary is currently unavailable."
            }

            // 7. Road Signs, Traffic Lights & Number Plates (100% Offline Vision)
            q.contains("last sign") || q.contains("what sign") || q.contains("previous sign") || q.contains("road sign") || q.contains("sign") || q.contains("alama") -> {
                val res = parseObj(toolExecutor.executeTool("get_recent_road_observations", emptyObj()))
                val lastSign = res["last_sign"]?.jsonPrimitive?.content
                val limit = res["last_speed_limit_kmh"]?.jsonPrimitive?.content
                when {
                    lastSign != null && lastSign != "none" && lastSign != "null" ->
                        "The last confirmed road sign was $lastSign."
                    limit != null && limit != "null" ->
                        "The last recognized speed limit was $limit kilometres per hour."
                    else -> "No road sign has been confirmed yet."
                }
            }
            q.contains("traffic light") || q.contains("red light") || q.contains("green light") -> {
                val res = parseObj(toolExecutor.executeTool("get_recent_road_observations", emptyObj()))
                val signal = res["traffic_light"]?.jsonPrimitive?.content ?: "NONE"
                if (signal == "NONE") "No traffic light is currently detected ahead."
                else "Detected traffic light signal is $signal."
            }
            q.contains("number plate") || q.contains("plate") -> {
                val res = parseObj(toolExecutor.executeTool("get_recent_road_observations", emptyObj()))
                val plate = res["last_plate"]?.jsonPrimitive?.content
                if (plate.isNullOrBlank() || plate == "null") "No vehicle number plate has been read yet."
                else "The last spotted number plate was $plate."
            }

            // 8. Speed & GPS Location (100% Offline)
            q.contains("speed") || q.contains("how fast") || q.contains("limit") || q.contains("kasi") -> {
                val res = parseObj(toolExecutor.executeTool("get_current_speed", emptyObj()))
                val speed = res["speed_kmh"]?.jsonPrimitive?.content
                val limit = res["observed_limit_kmh"]?.jsonPrimitive?.content
                val lastSign = res["last_sign"]?.jsonPrimitive?.content
                val speedPart = if (speed == null || speed == "null") {
                    if (language == "sw") "Kasi ya GPS haipatikani kwa sasa." else "GPS speed is currently unavailable."
                } else {
                    val rounded = speed.toFloatOrNull()?.toInt()?.toString() ?: speed
                    "Your current speed is $rounded kilometres per hour."
                }
                val limitPart = when {
                    limit != null && limit != "null" -> " The last recognized speed limit is $limit kilometres per hour."
                    lastSign != null && lastSign != "none" -> " Last road sign seen: $lastSign."
                    else -> ""
                }
                speedPart + limitPart
            }
            q.contains("location") || q.contains("where am i") || q.contains("gps") || q.contains("satellites") || q.contains("wapi") -> {
                val res = parseObj(toolExecutor.executeTool("get_current_location", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "Current GPS location is unavailable."
                } else {
                    val sats = res["satellites"]?.jsonPrimitive?.content ?: "0"
                    "GPS is locked with $sats satellites at latitude ${res["latitude"]?.jsonPrimitive?.content}, longitude ${res["longitude"]?.jsonPrimitive?.content}."
                }
            }

            // 9. Weather, Route, Traffic & Trip Summary
            q.contains("weather") || q.contains("whether") || q.contains("forecast") || q.contains("rain") || q.contains("hali ya hewa") -> {
                val res = parseObj(toolExecutor.executeTool("get_weather_forecast", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "Weather forecast is currently unavailable."
                } else {
                    val cond = res["condition"]?.jsonPrimitive?.content ?: "Available"
                    val temp = res["temperature_c"]?.jsonPrimitive?.content
                    val tempPart = if (temp != null && temp != "null") " at $temp degrees Celsius" else ""
                    "Weather is $cond$tempPart."
                }
            }
            q.contains("traffic") || q.contains("msongamano") -> {
                val res = parseObj(toolExecutor.executeTool("get_traffic_conditions", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "No active route to check traffic."
                } else {
                    "Traffic status: ${res["traffic_status"]?.jsonPrimitive?.content}."
                }
            }
            q.contains("alternative") || q.contains("njia mbadala") -> {
                val res = parseObj(toolExecutor.executeTool("find_alternative_routes", emptyObj()))
                if (res["alternative_available"]?.jsonPrimitive?.content == "true") {
                    "Alternative via ${res["summary"]?.jsonPrimitive?.content} may save ${res["minutes_saved"]?.jsonPrimitive?.content} minutes."
                } else {
                    "No faster alternative route is available right now."
                }
            }
            q.contains("route") || q.contains("arrive") || q.contains("eta") -> {
                val res = parseObj(toolExecutor.executeTool("get_active_route", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "No active navigation route."
                } else {
                    "Destination ${res["destination"]?.jsonPrimitive?.content}, ${res["eta_minutes"]?.jsonPrimitive?.content} minutes remaining."
                }
            }
            q.contains("trip") || q.contains("distance") || q.contains("safari") -> {
                val res = parseObj(toolExecutor.executeTool("get_trip_summary", emptyObj()))
                "Current trip distance is ${res["current_trip_distance_km"]?.jsonPrimitive?.content} kilometres."
            }
            q.contains("petrol station") || q.contains("hospital") || q.contains("nearest") -> {
                val args = buildJsonObject { put("query", utterance) }
                val res = parseObj(toolExecutor.executeTool("search_nearby_places", args))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "Nearby place search is unavailable offline."
                } else {
                    "Nearest match: ${res["nearest_name"]?.jsonPrimitive?.content}, ${res["address"]?.jsonPrimitive?.content}."
                }
            }
            q.contains("help") || q.contains("what can you do") || q.contains("commands") -> {
                "I work completely offline. Ask me to check your heartbeat, sleep, fuel consumption, battery voltage, engine RPM, coolant temperature, fault codes, speed, compass, road quality, or all sensors."
            }
            else -> {
                // 100% offline fallback: never stall waiting on internet
                "I heard $utterance. Ask me about your heartbeat, sleep, fuel consumption, battery voltage, engine RPM, coolant, fault codes, speed, road signs, or all sensors."
            }
        }
    }

    private fun parseObj(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(emptyObj())

    private fun emptyObj(): JsonObject = JsonObject(emptyMap())
}
