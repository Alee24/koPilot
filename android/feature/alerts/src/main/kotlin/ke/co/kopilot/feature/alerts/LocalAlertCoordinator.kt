package ke.co.kopilot.feature.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.AlertMessage
import ke.co.kopilot.core.common.model.AlertPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface SpeechSink {
    fun speak(text: String, flushQueue: Boolean, speechRate: Float, languageTag: String)
    fun stop()
}

@Singleton
class AndroidTtsSpeechSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechSink {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        audioManager.abandonAudioFocusRequest(focusRequest)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        audioManager.abandonAudioFocusRequest(focusRequest)
                    }
                })
            }
        }
    }

    fun playChime() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
        }
    }

    override fun speak(text: String, flushQueue: Boolean, speechRate: Float, languageTag: String) {
        if (!ready) return
        val preferred = if (languageTag == "sw") Locale("sw", "KE") else Locale("en", "KE")
        val langResult = tts?.setLanguage(preferred)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback = tts?.setLanguage(Locale.US)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        }
        tts?.setSpeechRate(speechRate)
        audioManager.requestAudioFocus(focusRequest)
        val mode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "kopilot_${System.currentTimeMillis()}")
    }

    override fun stop() {
        tts?.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

data class AlertCoordinatorState(
    val activeAlert: AlertMessage? = null,
    val mutedUntilMillis: Long = 0L,
    val alertsTriggeredCount: Int = 0,
    val demandingConditionsActive: Boolean = false,
)

@Singleton
class LocalAlertCoordinator @Inject constructor(
    private val speechSink: SpeechSink,
) {
    private val _state = MutableStateFlow(AlertCoordinatorState())
    val state: StateFlow<AlertCoordinatorState> = _state.asStateFlow()

    private val lastSpokenByKey = mutableMapOf<String, Long>()
    private var currentSpeakingPriority: AlertPriority? = null
    private var currentSpeakingUntilMillis: Long = 0L

    fun submit(
        alert: AlertMessage,
        speechRate: Float = 1.0f,
        language: String = "en",
        globalMute: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (globalMute || nowMillis < _state.value.mutedUntilMillis) {
            return false
        }

        if (_state.value.demandingConditionsActive && alert.priority.rank >= AlertPriority.CONVERSATIONAL_RESPONSE.rank) {
            return false
        }

        val lastSeen = lastSpokenByKey[alert.dedupKey]
        if (lastSeen != null && (nowMillis - lastSeen) < cooldownFor(alert.priority)) {
            return false
        }

        val isSpeaking = nowMillis < currentSpeakingUntilMillis
        if (isSpeaking && currentSpeakingPriority != null && alert.priority.rank > currentSpeakingPriority!!.rank) {
            return false
        }

        lastSpokenByKey[alert.dedupKey] = nowMillis
        currentSpeakingPriority = alert.priority
        currentSpeakingUntilMillis = nowMillis + 4_500L

        val flush = isSpeaking && currentSpeakingPriority != null && alert.priority.rank < currentSpeakingPriority!!.rank
        speechSink.speak(alert.spokenText, flushQueue = flush, speechRate = speechRate, languageTag = language)

        _state.value = _state.value.copy(
            activeAlert = alert,
            alertsTriggeredCount = _state.value.alertsTriggeredCount + 1,
        )
        return true
    }

    fun muteForMinutes(minutes: Int, nowMillis: Long = System.currentTimeMillis()) {
        speechSink.stop()
        _state.value = _state.value.copy(mutedUntilMillis = nowMillis + minutes * 60_000L)
    }

    fun clearMute() {
        _state.value = _state.value.copy(mutedUntilMillis = 0L)
    }

    fun setDemandingConditions(active: Boolean) {
        _state.value = _state.value.copy(demandingConditionsActive = active)
    }

    private fun cooldownFor(priority: AlertPriority): Long = when (priority) {
        AlertPriority.ROAD_OBSERVATION -> 20_000L
        AlertPriority.NAVIGATION_INSTRUCTION -> 8_000L
        AlertPriority.TRAFFIC_UPDATE -> 60_000L
        AlertPriority.WEATHER_NOTIFICATION -> 180_000L
        AlertPriority.VEHICLE_DIAGNOSTIC -> 120_000L
        AlertPriority.CONVERSATIONAL_RESPONSE -> 2_000L
    }
}
