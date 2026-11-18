package ke.co.kopilot.feature.soundstudio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundPackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val prefs = context.getSharedPreferences("kopilot_sound_studio", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<EngineSoundProfile>>(BuiltInEngineProfiles.ALL)
    val profiles: StateFlow<List<EngineSoundProfile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(loadAudioSettings())
    val settings: StateFlow<EngineAudioSettings> = _settings.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(
        prefs.getString("selected_profile_id", BuiltInEngineProfiles.ALL.first().id)
            ?: BuiltInEngineProfiles.ALL.first().id,
    )
    val selectedProfileId: StateFlow<String> = _selectedProfileId.asStateFlow()

    init {
        reloadAllProfiles()
    }

    private fun packsDir(): File = File(context.filesDir, "sound_packs").apply { if (!exists()) mkdirs() }

    fun reloadAllProfiles() {
        val custom = packsDir().listFiles()?.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val manifest = File(dir, "manifest.json")
            if (!manifest.exists()) return@mapNotNull null
            runCatching { json.decodeFromString<EngineSoundProfile>(manifest.readText()) }.getOrNull()
        }.orEmpty()
        _profiles.value = BuiltInEngineProfiles.ALL + custom
    }

    fun selectProfile(id: String) {
        _selectedProfileId.value = id
        prefs.edit().putString("selected_profile_id", id).apply()
    }

    fun updateAudioSettings(transform: (EngineAudioSettings) -> EngineAudioSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString("audio_settings_json", json.encodeToString(next)).apply()
    }

    fun saveCustomSoundPack(
        name: String,
        description: String,
        configuration: String,
        idleRpm: Int,
        redlineRpm: Int,
        samples: Map<RpmSampleSlot, Pair<ValidatedWavAudio, Pair<Int, Int>>>,
    ): Result<EngineSoundProfile> = runCatching {
        require(name.isNotBlank()) { "Sound pack name is required." }
        require(samples.isNotEmpty()) { "At least one WAV sample is required." }

        val id = "custom_${UUID.randomUUID().toString().take(8)}"
        val packDir = File(packsDir(), id).apply { mkdirs() }
        val layers = mutableListOf<EngineSampleLayer>()

        samples.forEach { (slot, pair) ->
            val (wav, range) = pair
            val outFileName = "${slot.name.lowercase()}.wav"
            File(packDir, outFileName).writeBytes(wav.rawBytes)
            layers += EngineSampleLayer(
                slot = slot,
                fileName = outFileName,
                minRpm = range.first.coerceIn(400, 12000),
                maxRpm = range.second.coerceAtLeast(range.first + 200),
                baseRpm = ((range.first + range.second) / 2).coerceIn(500, 10000),
                sampleRateHz = wav.sampleRateHz,
                durationSec = wav.durationSec,
            )
        }

        val profile = EngineSoundProfile(
            id = id,
            name = name.trim(),
            category = EngineCategory.CUSTOM_IMPORTED,
            description = description.ifBlank { "Custom imported WAV sound pack." },
            configuration = configuration.ifBlank { "Custom WAV Multi-Layer (${layers.size} samples)" },
            characteristics = "User-imported offline WAV recordings",
            idleRpm = idleRpm.coerceIn(450, 2000),
            redlineRpm = redlineRpm.coerceIn(2000, 11000),
            isCustomImported = true,
            sampleLayers = layers,
        )
        File(packDir, "manifest.json").writeText(json.encodeToString(profile))
        reloadAllProfiles()
        selectProfile(profile.id)
        profile
    }

    fun deleteCustomSoundPack(profileId: String) {
        val dir = File(packsDir(), profileId)
        if (dir.exists()) dir.deleteRecursively()
        reloadAllProfiles()
        if (_selectedProfileId.value == profileId) {
            selectProfile(BuiltInEngineProfiles.ALL.first().id)
        }
    }

    fun loadDecodedSamplesForProfile(profile: EngineSoundProfile): Map<RpmSampleSlot, FloatArray> {
        if (!profile.isCustomImported || profile.sampleLayers.isEmpty()) return emptyMap()
        val dir = File(packsDir(), profile.id)
        val result = mutableMapOf<RpmSampleSlot, FloatArray>()
        for (layer in profile.sampleLayers) {
            val file = File(dir, layer.fileName)
            if (!file.exists()) continue
            val parsed = WavAudioLoader.parseAndValidateWav(layer.fileName, file.readBytes())
            if (parsed is WavValidationResult.Valid) {
                result[layer.slot] = parsed.audio.monoPcm44100
            }
        }
        return result
    }

    private fun loadAudioSettings(): EngineAudioSettings {
        val raw = prefs.getString("audio_settings_json", null) ?: return EngineAudioSettings()
        return runCatching { json.decodeFromString<EngineAudioSettings>(raw) }.getOrDefault(EngineAudioSettings())
    }
}
