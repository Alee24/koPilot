package ke.co.kopilot.feature.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.core.network.dto.VoiceSessionRequestDto
import ke.co.kopilot.core.security.SecureTokenStore
import ke.co.kopilot.feature.alerts.SpeechSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class VoiceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOCAL_FALLBACK,
    OFFLINE_UNAVAILABLE,
    ERROR,
}

data class VoiceAssistantState(
    val connectionState: VoiceConnectionState = VoiceConnectionState.DISCONNECTED,
    val micPermissionGranted: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val bluetoothAudioActive: Boolean = false,
    val lastTranscript: String = "",
    val statusMessage: String = "Voice assistant idle",
)

@Singleton
class OpenAiRealtimeVoiceClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: KoPilotApiService,
    private val tokenStore: SecureTokenStore,
    private val connectivityObserver: ConnectivityObserver,
    private val toolExecutor: VoiceToolExecutor,
    private val localVoiceEngine: LocalVoiceCommandEngine,
    private val speechSink: SpeechSink,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val json = Json { ignoreUnknownKeys = true }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(VoiceAssistantState())
    val state: StateFlow<VoiceAssistantState> = _state.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var localAudioTrack: AudioTrack? = null

    fun setMicPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(
            micPermissionGranted = granted,
            statusMessage = if (granted) "Voice assistant ready" else "Microphone permission required",
        )
    }

    fun toggleMute() {
        val nextMuted = !_state.value.isMuted
        localAudioTrack?.setEnabled(!nextMuted)
        _state.value = _state.value.copy(
            isMuted = nextMuted,
            isListening = !nextMuted && _state.value.connectionState == VoiceConnectionState.CONNECTED,
        )
    }

    fun connect(language: String = "en", voice: String? = null) {
        if (!_state.value.micPermissionGranted) {
            _state.value = _state.value.copy(
                connectionState = VoiceConnectionState.ERROR,
                statusMessage = "Microphone permission required",
            )
            return
        }
        if (!connectivityObserver.isCurrentlyOnline()) {
            startLocalVoiceFallback(language, "Offline: using local voice")
            return
        }
        if (_state.value.connectionState == VoiceConnectionState.CONNECTING ||
            _state.value.connectionState == VoiceConnectionState.CONNECTED
        ) {
            return
        }

        _state.value = _state.value.copy(
            connectionState = VoiceConnectionState.CONNECTING,
            statusMessage = "Connecting voice...",
        )

        scope.launch {
            runCatching {
                configureAudioRouting()
                val directKey = tokenStore.getOpenAiApiKey()
                val (secret, callsUrl) = if (directKey != null) {
                    mintDirectEphemeralSecret(directKey, language, voice ?: "marin")
                } else {
                    val session = apiService.createVoiceSession(
                        VoiceSessionRequestDto(
                            deviceId = tokenStore.getOrCreateDeviceId(),
                            language = language,
                            voice = voice,
                        ),
                    )
                    session.clientSecret to session.webrtcCallsUrl
                }
                establishWebRtcSession(
                    ephemeralSecret = secret,
                    callsUrl = callsUrl,
                    language = language,
                )
            }.onFailure {
                startLocalVoiceFallback(language, "Cloud voice unavailable; listening locally")
            }
        }
    }

    private suspend fun mintDirectEphemeralSecret(
        apiKey: String,
        language: String,
        voice: String,
    ): Pair<String, String> = withContext(dispatchers.io) {
        val bodyJson = """
            {
              "expires_after": {"anchor": "created_at", "seconds": 600},
              "session": {
                "type": "realtime",
                "model": "gpt-realtime",
                "audio": {"output": {"voice": "$voice"}}
              }
            }
        """.trimIndent()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/realtime/client_secrets")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("OpenAI client_secrets HTTP ${resp.code}")
            val raw = resp.body?.string() ?: error("Empty OpenAI response")
            val obj = json.parseToJsonElement(raw).jsonObject
            val value = obj["value"]?.jsonPrimitive?.content
                ?: obj["client_secret"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                ?: error("Missing ephemeral secret")
            value to "https://api.openai.com/v1/realtime/calls"
        }
    }

    private fun startLocalVoiceFallback(language: String, reason: String) {
        _state.value = _state.value.copy(
            connectionState = VoiceConnectionState.LOCAL_FALLBACK,
            isListening = true,
            statusMessage = reason,
        )
        localVoiceEngine.startListening(
            language = language,
            onListeningChanged = { listening ->
                _state.value = _state.value.copy(isListening = listening)
            },
            onResultSpoken = { spoken, reply ->
                _state.value = _state.value.copy(
                    connectionState = VoiceConnectionState.LOCAL_FALLBACK,
                    isSpeaking = true,
                    lastTranscript = "\"$spoken\" -> $reply",
                    statusMessage = "Local voice ready",
                )
                speechSink.speak(reply, flushQueue = true, speechRate = 1.0f, languageTag = language)
                _state.value = _state.value.copy(isSpeaking = false)
            },
            onError = { errMsg ->
                _state.value = _state.value.copy(
                    connectionState = VoiceConnectionState.DISCONNECTED,
                    isListening = false,
                    statusMessage = errMsg,
                )
            },
        )
    }

    private fun configureAudioRouting() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val btDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (btDevice != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.setCommunicationDevice(btDevice)
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            _state.value = _state.value.copy(bluetoothAudioActive = true)
        } else {
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            _state.value = _state.value.copy(bluetoothAudioActive = false)
        }
    }

    private suspend fun establishWebRtcSession(
        ephemeralSecret: String,
        callsUrl: String,
        language: String,
    ) {
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
        val pcFactory = factory ?: error("WebRTC factory initialization failed")
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())

        val pc = pcFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        newState == PeerConnection.IceConnectionState.FAILED
                    ) {
                        _state.value = _state.value.copy(
                            connectionState = VoiceConnectionState.DISCONNECTED,
                            isListening = false,
                            isSpeaking = false,
                            statusMessage = "Voice connection lost",
                        )
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidate(candidate: IceCandidate?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dc: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    (receiver?.track() as? AudioTrack)?.setEnabled(true)
                }
            },
        ) ?: error("Failed to create PeerConnection")

        peerConnection = pc

        val audioSource = pcFactory.createAudioSource(MediaConstraints())
        val audioTrack = pcFactory.createAudioTrack("kopilot_mic", audioSource)
        audioTrack.setEnabled(!_state.value.isMuted)
        localAudioTrack = audioTrack
        pc.addTrack(audioTrack)

        val channel = pc.createDataChannel("oai-events", DataChannel.Init())
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    sendEvent(VoiceToolSchemaRegistry.buildSessionUpdateJson(language))
                    _state.value = _state.value.copy(
                        connectionState = VoiceConnectionState.CONNECTED,
                        isListening = !_state.value.isMuted,
                        statusMessage = "Listening",
                    )
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleRealtimeEvent(String(bytes, Charsets.UTF_8))
            }
        })

        val offer = createOfferSuspend(pc)
        setLocalDescriptionSuspend(pc, offer)

        val answerSdp = withContext(dispatchers.io) {
            val req = Request.Builder()
                .url(callsUrl)
                .addHeader("Authorization", "Bearer $ephemeralSecret")
                .post(offer.description.toRequestBody("application/sdp".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Realtime WebRTC handshake failed: HTTP ${resp.code}")
                }
                resp.body?.string() ?: error("Empty SDP answer from OpenAI Realtime")
            }
        }

        setRemoteDescriptionSuspend(pc, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    private fun handleRealtimeEvent(rawJson: String) {
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "input_audio_buffer.speech_started" -> {
                _state.value = _state.value.copy(isSpeaking = false, isListening = true)
            }
            "response.output_audio.delta" -> {
                _state.value = _state.value.copy(isSpeaking = true)
            }
            "response.output_audio.done" -> {
                _state.value = _state.value.copy(isSpeaking = false)
            }
            "response.output_audio_transcript.done" -> {
                val transcript = obj["transcript"]?.jsonPrimitive?.content.orEmpty()
                if (transcript.isNotBlank()) {
                    _state.value = _state.value.copy(lastTranscript = transcript)
                }
            }
            "response.function_call_arguments.done" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: return
                val argsText = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
                val argsObj = runCatching { json.parseToJsonElement(argsText).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
                scope.launch {
                    val resultJson = toolExecutor.executeTool(name, argsObj)
                    val escaped = resultJson.replace("\\", "\\\\").replace("\"", "\\\"")
                    sendEvent(
                        """{"type":"conversation.item.create","item":{"type":"function_call_output","call_id":"$callId","output":"$escaped"}}""",
                    )
                    sendEvent("""{"type":"response.create"}""")
                }
            }
        }
    }

    private fun sendEvent(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun disconnect() {
        localVoiceEngine.stop()
        runCatching {
            dataChannel?.close()
            peerConnection?.close()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        dataChannel = null
        peerConnection = null
        localAudioTrack = null
        _state.value = _state.value.copy(
            connectionState = VoiceConnectionState.DISCONNECTED,
            isListening = false,
            isSpeaking = false,
            statusMessage = "Voice assistant disconnected",
        )
    }

    private suspend fun createOfferSuspend(pc: PeerConnection): SessionDescription =
        suspendCoroutine { cont ->
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            pc.createOffer(
                object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                    override fun onCreateFailure(error: String?) = cont.resumeWithException(RuntimeException(error))
                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(error: String?) = Unit
                },
                constraints,
            )
        }

    private suspend fun setLocalDescriptionSuspend(pc: PeerConnection, sdp: SessionDescription) =
        suspendCoroutine { cont ->
            pc.setLocalDescription(
                object : SdpObserver {
                    override fun onSetSuccess() = cont.resume(Unit)
                    override fun onSetFailure(error: String?) = cont.resumeWithException(RuntimeException(error))
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                },
                sdp,
            )
        }

    private suspend fun setRemoteDescriptionSuspend(pc: PeerConnection, sdp: SessionDescription) =
        suspendCoroutine { cont ->
            pc.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() = cont.resume(Unit)
                    override fun onSetFailure(error: String?) = cont.resumeWithException(RuntimeException(error))
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                },
                sdp,
            )
        }
}
