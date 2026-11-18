package ke.co.kopilot.feature.soundstudio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.feature.alerts.LocalAlertCoordinator
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.diagnostics.ObdConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SoundStudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val obdManager: ObdBluetoothManager,
    private val alertCoordinator: LocalAlertCoordinator,
    private val packRepo: SoundPackRepository,
    private val synthesizer: EngineAudioSynthesizer,
) : ViewModel() {

    private val smoother = ObdRpmSmoother()
    private val isRunningFlow = MutableStateFlow(false)
    private val isPreviewFlow = MutableStateFlow(false)
    private val previewRpmFlow = MutableStateFlow(850)
    private val isMutedFlow = MutableStateFlow(false)
    private val pauseReasonFlow = MutableStateFlow<String?>(null)
    private val liveTelemetryFlow = MutableStateFlow(LiveSmootherSnapshot())
    private val importStatusFlow = MutableStateFlow<String?>(null)

    private var controlLoopJob: Job? = null

    val importStatus: StateFlow<String?> = importStatusFlow

    val uiState: StateFlow<SoundStudioUiState> = combine(
        isRunningFlow,
        isPreviewFlow,
        previewRpmFlow,
        isMutedFlow,
        pauseReasonFlow,
        packRepo.profiles,
        packRepo.selectedProfileId,
        packRepo.settings,
        obdManager.connectionState,
        obdManager.liveTelemetry,
        liveTelemetryFlow,
        synthesizer.waveform,
        synthesizer.audioOutputRoute,
    ) { arr ->
        val running = arr[0] as Boolean
        val preview = arr[1] as Boolean
        val previewRpm = arr[2] as Int
        val muted = arr[3] as Boolean
        val pauseReason = arr[4] as String?
        @Suppress("UNCHECKED_CAST")
        val profiles = arr[5] as List<EngineSoundProfile>
        val selectedId = arr[6] as String
        val settings = arr[7] as EngineAudioSettings
        val connState = arr[8] as ObdConnectionState
        val obdTel = arr[9] as ke.co.kopilot.feature.diagnostics.ObdLiveTelemetry
        val smooth = arr[10] as LiveSmootherSnapshot
        @Suppress("UNCHECKED_CAST")
        val waveform = arr[11] as List<Float>
        val route = arr[12] as String

        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        val obdConnected = connState == ObdConnectionState.CONNECTED

        SoundStudioUiState(
            isRunning = running,
            isPreviewActive = preview,
            previewRpm = previewRpm,
            isMuted = muted,
            isDuckedOrPausedReason = pauseReason,
            obdConnected = obdConnected,
            obdStatusLabel = when {
                obdConnected -> "OBD-II Connected (${obdTel.engineRpm ?: "--"} RPM)"
                else -> "OBD-II ${connState.name}"
            },
            liveRpm = if (preview) previewRpm.toFloat() else smooth.rpm,
            liveThrottlePct = obdTel.throttlePositionPct,
            liveSpeedKmh = obdTel.vehicleSpeedKmh,
            liveLoadPct = obdTel.engineLoadPct,
            isObdStale = smooth.isStale,
            audioOutputRoute = route,
            audioLatencyEstimateMs = if (settings.batterySavingMode) 45 else 24,
            selectedProfile = selected,
            profiles = profiles,
            audioSettings = settings,
            waveformSnapshot = waveform,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SoundStudioUiState(),
    )

    init {
        viewModelScope.launch {
            combine(packRepo.profiles, packRepo.selectedProfileId) { profiles, id ->
                profiles.firstOrNull { it.id == id } ?: profiles.first()
            }.collect { profile ->
                val decoded = withContext(Dispatchers.IO) {
                    packRepo.loadDecodedSamplesForProfile(profile)
                }
                synthesizer.setProfile(profile, decoded)
            }
        }
        viewModelScope.launch {
            packRepo.settings.collect { settings ->
                synthesizer.setSettings(settings)
            }
        }
    }

    fun selectProfile(profileId: String) {
        packRepo.selectProfile(profileId)
    }

    fun setSimulationMode(mode: EngineSimulationMode) {
        packRepo.updateAudioSettings { it.copy(simulationMode = mode) }
    }

    fun updateSettings(transform: (EngineAudioSettings) -> EngineAudioSettings) {
        packRepo.updateAudioSettings(transform)
    }

    fun toggleMute() {
        isMutedFlow.value = !isMutedFlow.value
    }

    fun toggleEngineSound() {
        if (isRunningFlow.value) {
            stopAllAudio()
        } else {
            isPreviewFlow.value = false
            isRunningFlow.value = true
            smoother.reset()
            synthesizer.start()
            ensureControlLoop()
        }
    }

    fun startParkedPreview(rpm: Int = uiState.value.selectedProfile.idleRpm) {
        isRunningFlow.value = false
        isPreviewFlow.value = true
        previewRpmFlow.value = rpm.coerceIn(500, uiState.value.selectedProfile.redlineRpm)
        synthesizer.start()
        ensureControlLoop()
    }

    fun setPreviewRpm(rpm: Int) {
        previewRpmFlow.value = rpm.coerceIn(500, uiState.value.selectedProfile.redlineRpm)
    }

    fun stopAllAudio() {
        isRunningFlow.value = false
        isPreviewFlow.value = false
        pauseReasonFlow.value = null
        controlLoopJob?.cancel()
        controlLoopJob = null
        synthesizer.stop()
        smoother.reset()
        liveTelemetryFlow.value = LiveSmootherSnapshot()
    }

    private fun ensureControlLoop() {
        controlLoopJob?.cancel()
        controlLoopJob = viewModelScope.launch(Dispatchers.Default) {
            var lastTickMs = System.currentTimeMillis()
            while (isActive && (isRunningFlow.value || isPreviewFlow.value)) {
                val now = System.currentTimeMillis()
                val dtSec = ((now - lastTickMs) / 1000f).coerceIn(0.01f, 0.25f)
                lastTickMs = now

                val settings = packRepo.settings.value
                val alertsActive = alertCoordinator.state.value.activeAlert != null
                val phoneCallActive = synthesizer.isPhoneCallActive()

                if (isPreviewFlow.value) {
                    val muted = isMutedFlow.value || (settings.muteOnPhoneCall && phoneCallActive)
                    val gain = if (muted) 0f else if (alertsActive && settings.pauseOnSafetyAlert) 0.15f else 1f
                    pauseReasonFlow.value = when {
                        muted -> "Muted"
                        alertsActive && settings.pauseOnSafetyAlert -> "Ducked for Safety Alert"
                        else -> null
                    }
                    synthesizer.updateParameters(
                        rpm = previewRpmFlow.value.toFloat(),
                        throttle = 0.45f,
                        rpmDeltaPerSec = 0f,
                        masterGain = gain,
                    )
                } else {
                    val obdConnected = obdManager.connectionState.value == ObdConnectionState.CONNECTED
                    val tel = obdManager.liveTelemetry.value
                    val processed = smoother.update(
                        obdConnected = obdConnected,
                        rawRpm = tel.engineRpm,
                        rawThrottlePct = tel.throttlePositionPct,
                        rawEngineLoadPct = tel.engineLoadPct,
                        sampleTimestampMs = tel.lastUpdatedMillis ?: 0L,
                        nowMs = now,
                        responsiveness = settings.rpmResponsiveness,
                        dtSec = dtSec,
                    )
                    liveTelemetryFlow.value = LiveSmootherSnapshot(
                        rpm = processed.smoothedRpm,
                        isStale = processed.isStale,
                    )

                    val safetyMute = isMutedFlow.value ||
                        (settings.muteOnPhoneCall && phoneCallActive) ||
                        (settings.pauseOnSafetyAlert && alertsActive) ||
                        (settings.pauseOnObdDisconnect && processed.isStale)

                    pauseReasonFlow.value = when {
                        isMutedFlow.value -> "Muted by driver"
                        settings.muteOnPhoneCall && phoneCallActive -> "Muted for phone call"
                        settings.pauseOnSafetyAlert && alertsActive -> "Paused for safety alert"
                        processed.isStale -> "Paused — waiting for live OBD-II RPM"
                        else -> null
                    }

                    val gain = if (safetyMute) 0f else processed.safeOutputScale
                    synthesizer.updateParameters(
                        rpm = processed.smoothedRpm,
                        throttle = processed.throttleFraction,
                        rpmDeltaPerSec = processed.rpmDeltaPerSec,
                        masterGain = gain,
                    )
                }

                synthesizer.refreshOutputRoute()
                delay(if (settings.batterySavingMode) 90L else 45L)
            }
        }
    }

    suspend fun validateWavUri(uri: Uri): WavValidationResult = withContext(Dispatchers.IO) {
        WavAudioLoader.importFromUri(context, uri)
    }

    fun saveCustomPack(
        name: String,
        description: String,
        configuration: String,
        idleRpm: Int,
        redlineRpm: Int,
        samples: Map<RpmSampleSlot, Pair<ValidatedWavAudio, Pair<Int, Int>>>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = packRepo.saveCustomSoundPack(name, description, configuration, idleRpm, redlineRpm, samples)
            importStatusFlow.value = res.fold(
                onSuccess = { "Saved custom sound pack '${it.name}'" },
                onFailure = { "Import failed: ${it.localizedMessage ?: "error"}" },
            )
        }
    }

    fun deleteCustomPack(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            packRepo.deleteCustomSoundPack(profileId)
            importStatusFlow.value = "Deleted custom sound pack."
        }
    }

    fun clearImportStatus() {
        importStatusFlow.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAllAudio()
    }

    private data class LiveSmootherSnapshot(
        val rpm: Float = 0f,
        val isStale: Boolean = true,
    )
}
