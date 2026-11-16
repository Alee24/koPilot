package ke.co.kopilot.feature.voice

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalVoiceCommandEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolExecutor: VoiceToolExecutor,
    private val tokenStore: SecureTokenStore,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private var recognizer: SpeechRecognizer? = null

    fun startListening(
        language: String,
        onListeningChanged: (Boolean) -> Unit,
        onResultSpoken: (transcript: String, responseText: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("On-device speech recognition service is not installed on this device.")
                return@post
            }

            recognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListeningChanged(true)
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onListeningChanged(false)

                override fun onError(error: Int) {
                    onListeningChanged(false)
                    onError("Speech recognition error ($error). Tap Talk to retry.")
                }

                override fun onResults(results: Bundle?) {
                    onListeningChanged(false)
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (spoken.isBlank()) {
                        onError("Did not catch that. Please try again.")
                        return
                    }
                    scope.launch {
                        val reply = handleSpokenCommand(spoken, language)
                        onResultSpoken(spoken, reply)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "sw") "sw-KE" else "en-KE")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            sr.startListening(intent)
        }
    }

    fun stop() {
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
    }

    internal suspend fun handleSpokenCommand(utterance: String, language: String): String {
        val q = utterance.lowercase()
        return when {
            q.contains("speed") || q.contains("kasi") -> {
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
                    else -> " No speed limit sign has been recognized yet."
                }
                speedPart + limitPart
            }
            q.contains("location") || q.contains("where am i") || q.contains("wapi") -> {
                val res = parseObj(toolExecutor.executeTool("get_current_location", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "Current GPS location is unavailable."
                } else {
                    "GPS is active at latitude ${res["latitude"]?.jsonPrimitive?.content}, longitude ${res["longitude"]?.jsonPrimitive?.content}."
                }
            }
            q.contains("weather") || q.contains("rain") || q.contains("hali ya hewa") -> {
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
            q.contains("sign") || q.contains("alama") -> {
                val res = parseObj(toolExecutor.executeTool("get_recent_road_observations", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "No traffic signs have been observed recently."
                } else {
                    "Last observed sign: ${res["sign"]?.jsonPrimitive?.content}."
                }
            }
            q.contains("engine") || q.contains("diagnostic") || q.contains("obd") || q.contains("vehicle") -> {
                val res = parseObj(toolExecutor.executeTool("get_vehicle_diagnostics", emptyObj()))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    res["reason"]?.jsonPrimitive?.content ?: "OBD-II diagnostics are unavailable."
                } else {
                    "Engine RPM ${res["rpm"]?.jsonPrimitive?.content}, coolant ${res["coolant_c"]?.jsonPrimitive?.content} degrees."
                }
            }
            q.contains("trip") || q.contains("safari") -> {
                val res = parseObj(toolExecutor.executeTool("get_trip_summary", emptyObj()))
                "Current trip distance is ${res["current_trip_distance_km"]?.jsonPrimitive?.content} kilometres."
            }
            q.contains("petrol") || q.contains("fuel") || q.contains("hospital") || q.contains("nearest") -> {
                val args = buildJsonObject { put("query", utterance) }
                val res = parseObj(toolExecutor.executeTool("search_nearby_places", args))
                if (res["status"]?.jsonPrimitive?.content != "ok") {
                    "Nearby place search is unavailable right now."
                } else {
                    "Nearest match: ${res["nearest_name"]?.jsonPrimitive?.content}, ${res["address"]?.jsonPrimitive?.content}."
                }
            }
            else -> {
                val apiKey = tokenStore.getOpenAiApiKey()
                if (apiKey != null) {
                    askOpenAiGeneralTopic(apiKey, utterance, language)
                } else {
                    "Heard: $utterance. Add your OpenAI API key in Settings to ask about any topic, or ask about your speed, route, traffic, weather, road signs, or vehicle diagnostics."
                }
            }
        }
    }

    private suspend fun askOpenAiGeneralTopic(
        apiKey: String,
        question: String,
        language: String,
    ): String = withContext(dispatchers.io) {
        val speedContext = runCatching { toolExecutor.executeTool("get_current_speed", emptyObj()) }.getOrDefault("")
        val langNote = if (language == "sw") "Answer in natural Kiswahili." else "Answer clearly in English."
        val sysPrompt = "You are KoPilot, an intelligent AI driving companion for Kenyan motorists. " +
            "Answer any question on any topic clearly and concisely (2-4 spoken sentences) so it can be read out loud while driving. " +
            "Live vehicle context if relevant: $speedContext. $langNote"

        val payload = buildJsonObject {
            put("model", "gpt-4o-mini")
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", sysPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", question) })
                },
            )
            put("max_tokens", 220)
        }.toString()

        runCatching {
            val req = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching "OpenAI returned HTTP ${resp.code}. Please check your API key in Settings."
                }
                val body = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
                    ?.trim()
                    ?.ifBlank { "I could not generate a response." }
                    ?: "I could not generate a response."
            }
        }.getOrElse { err ->
            "Could not reach OpenAI: ${err.localizedMessage ?: "network error"}."
        }
    }

    private fun parseObj(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(emptyObj())

    private fun emptyObj(): JsonObject = JsonObject(emptyMap())
}
