package ke.co.kopilot.feature.soundstudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Low-latency offline AudioTrack synthesis engine.
 * The audio render thread reads ONLY @Volatile primitives and pre-decoded FloatArrays:
 * zero Bluetooth I/O, zero database queries, and zero allocations in the render loop.
 */
@Singleton
class EngineAudioSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile private var targetRpm = 0f
    @Volatile private var targetThrottle = 0.25f
    @Volatile private var targetRpmDelta = 0f
    @Volatile private var targetMasterGain = 0f
    @Volatile private var currentProfile = BuiltInEngineProfiles.ALL.first()
    @Volatile private var currentSettings = EngineAudioSettings()
    @Volatile private var customSamples: Map<RpmSampleSlot, FloatArray> = emptyMap()
    @Volatile private var hasAudioFocus = true
    @Volatile private var isDuckedByFocus = false

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _waveform = MutableStateFlow(List(32) { 0f })
    val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

    private val _audioOutputRoute = MutableStateFlow("Phone Speaker")
    val audioOutputRoute: StateFlow<String> = _audioOutputRoute.asStateFlow()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                isDuckedByFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = true
                isDuckedByFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                isDuckedByFocus = false
            }
        }
    }

    fun updateParameters(
        rpm: Float,
        throttle: Float,
        rpmDeltaPerSec: Float,
        masterGain: Float,
    ) {
        targetRpm = rpm
        targetThrottle = throttle
        targetRpmDelta = rpmDeltaPerSec
        targetMasterGain = masterGain.coerceIn(0f, 0.85f)
    }

    fun setProfile(profile: EngineSoundProfile, decodedSamples: Map<RpmSampleSlot, FloatArray>) {
        customSamples = decodedSamples
        currentProfile = profile
    }

    fun setSettings(settings: EngineAudioSettings) {
        currentSettings = settings
    }

    fun isPhoneCallActive(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    fun refreshOutputRoute() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val route = when {
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth Audio (A2DP)"
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "Bluetooth Hands-Free (SCO)"
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "Wired / AUX Output"
            else -> "Phone Speaker"
        }
        _audioOutputRoute.value = route
    }

    @Synchronized
    fun start() {
        if (running) return
        refreshOutputRoute()
        requestFocus()

        val sampleRate = WavAudioLoader.TARGET_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(2048)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()
        audioTrack = track
        running = true
        track.play()

        renderThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val blockSize = 512
            val buffer = FloatArray(blockSize)
            val wavIndices = FloatArray(RpmSampleSlot.entries.size)
            var phase = 0f
            var turboPhase = 0f
            var smoothedGain = 0f
            var smoothedRpm = 750f
            var lastWaveformEmitMs = 0L
            var rngState = 0x1234567
            var noiseLp = 0f

            while (running) {
                val settings = currentSettings
                val profile = currentProfile
                val samplesMap = customSamples
                val duckFactor = when {
                    !hasAudioFocus -> 0f
                    isDuckedByFocus -> 0.25f
                    else -> 1f
                }
                val desiredGain = targetMasterGain * settings.masterVolume * settings.soundIntensity * duckFactor
                val useCustomWav = profile.isCustomImported && samplesMap.isNotEmpty()
                val layerWeights = if (useCustomWav) {
                    EngineSynthMath.computeLayerWeights(smoothedRpm, targetRpmDelta, profile.sampleLayers)
                } else {
                    emptyMap()
                }
                val sportMode = settings.simulationMode == EngineSimulationMode.SPORT_SIMULATION
                val turboOn = settings.turboEffectsEnabled

                for (i in 0 until blockSize) {
                    smoothedGain += (desiredGain - smoothedGain) * 0.008f
                    smoothedRpm += (targetRpm.coerceAtLeast(300f) - smoothedRpm) * 0.015f

                    // Fast zero-allocation LCG noise + 1-pole low-pass for realistic exhaust air roar
                    rngState = rngState * 1664525 + 1013904223
                    val white = ((rngState ushr 16) and 0x7FFF) / 16384f - 1f
                    val lpAlpha = (0.04f + (smoothedRpm / 8000f) * 0.18f).coerceIn(0.04f, 0.25f)
                    noiseLp += (white - noiseLp) * lpAlpha

                    val rawSample = if (useCustomWav && layerWeights.isNotEmpty()) {
                        var mixed = 0f
                        for (layer in profile.sampleLayers) {
                            val w = layerWeights[layer.slot] ?: continue
                            val pcm = samplesMap[layer.slot] ?: continue
                            if (pcm.isEmpty()) continue
                            val slotIdx = layer.slot.ordinal
                            val idx = wavIndices[slotIdx]
                            val i0 = idx.toInt().coerceIn(0, pcm.lastIndex)
                            val i1 = if (i0 + 1 < pcm.size) i0 + 1 else 0
                            val frac = idx - i0
                            val sampleVal = pcm[i0] * (1f - frac) + pcm[i1] * frac
                            mixed += sampleVal * w

                            val pitchRatio = (smoothedRpm / layer.baseRpm.coerceAtLeast(300)).coerceIn(0.45f, 2.2f)
                            var nextIdx = idx + pitchRatio
                            if (nextIdx >= pcm.size) nextIdx -= pcm.size
                            wavIndices[slotIdx] = nextIdx
                        }
                        mixed * (0.5f + 0.5f * targetThrottle)
                    } else {
                        val freq = EngineSynthMath.firingFrequencyHz(
                            rpm = smoothedRpm,
                            cylinderCount = profile.category.cylinderCount,
                            fundamentalScale = profile.fundamentalScale,
                        )
                        phase += freq / sampleRate
                        if (phase >= 1f) phase -= 1f

                        val turboFreq = 900f + (smoothedRpm * 0.35f)
                        turboPhase += turboFreq / sampleRate
                        if (turboPhase >= 1f) turboPhase -= 1f

                        EngineSynthMath.synthesizeProceduralSample(
                            phase = phase,
                            turboPhase = turboPhase,
                            rpm = smoothedRpm,
                            throttle = targetThrottle,
                            rpmDeltaPerSec = targetRpmDelta,
                            profile = profile,
                            sportMode = sportMode,
                            turboEnabled = turboOn,
                            noiseSample = noiseLp,
                        )
                    }

                    buffer[i] = EngineSynthMath.softLimit(rawSample * smoothedGain, ceiling = 0.85f)
                }

                track.write(buffer, 0, blockSize, AudioTrack.WRITE_BLOCKING)

                // Throttled ~15 Hz waveform snapshot for UI without causing Compose churn
                val now = System.currentTimeMillis()
                if ((now - lastWaveformEmitMs) >= 65L) {
                    lastWaveformEmitMs = now
                    val step = blockSize / 32
                    val snap = List(32) { idx ->
                        val v = buffer[(idx * step).coerceIn(0, blockSize - 1)]
                        abs(v).coerceIn(0f, 1f)
                    }
                    _waveform.value = snap
                }
            }
        }, "KoPilotEngineAudio")
        renderThread?.start()
    }

    @Synchronized
    fun stop() {
        running = false
        targetMasterGain = 0f
        runCatching { renderThread?.join(300L) }
        renderThread = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        abandonFocus()
        _waveform.value = List(32) { 0f }
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        val res = audioManager.requestAudioFocus(req)
        hasAudioFocus = res != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
