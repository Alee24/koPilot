package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.HazardObservation
import ke.co.kopilot.core.common.model.TrafficSignCategory
import ke.co.kopilot.core.common.model.TrafficSignObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ModelRegistryEntry(
    val modelId: String,
    val version: String,
    val assetPath: String,
    val inputSize: Int,
    val classes: List<String>,
    val validationStatus: String,
    val knownLimitations: String,
)

data class VisionPipelineState(
    val modelLoaded: Boolean = false,
    val modelVersion: String = "unloaded",
    val validationStatus: String = "Unvalidated",
    val lastObservation: TrafficSignObservation? = null,
    val recentHazards: List<HazardObservation> = emptyList(),
    val lastInferenceLatencyMs: Long = 0L,
    val statusMessage: String = "Vision model not initialized",
)

@Singleton
class RoadVisionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val temporalTracker: SignTemporalTracker,
) {
    private val _state = MutableStateFlow(VisionPipelineState())
    val state: StateFlow<VisionPipelineState> = _state.asStateFlow()

    private var interpreter: Interpreter? = null
    private var activeEntry: ModelRegistryEntry? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun initializeIfNeeded() {
        if (interpreter != null) return
        runCatching {
            val registryText = context.assets.open("models/model_registry.json")
                .bufferedReader()
                .use { it.readText() }
            val entries = json.decodeFromString<List<ModelRegistryEntry>>(registryText)
            val entry = entries.firstOrNull() ?: error("Empty model registry")
            activeEntry = entry

            val modelBuffer = loadAssetFile(entry.assetPath)
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(modelBuffer, options)
            _state.value = _state.value.copy(
                modelLoaded = true,
                modelVersion = entry.version,
                validationStatus = entry.validationStatus,
                statusMessage = "LiteRT model ${entry.version} ready (${entry.validationStatus})",
            )
        }.onFailure { err ->
            _state.value = _state.value.copy(
                modelLoaded = false,
                statusMessage = "Sign model unavailable: ${err.localizedMessage ?: "asset not bundled"}",
            )
        }
    }

    fun analyzeFrame(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        timestampMillis: Long,
        onConfirmedSign: (TrafficSignObservation) -> Unit,
    ) {
        try {
            val tfl = interpreter
            val entry = activeEntry
            if (tfl == null || entry == null) {
                return
            }

            val startNs = System.nanoTime()
            val bitmap = imageProxy.toBitmap()
            val rotated = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            val scaled = Bitmap.createScaledBitmap(rotated, entry.inputSize, entry.inputSize, true)
            val inputBuffer = bitmapToFloatBuffer(scaled, entry.inputSize)

            val output = Array(1) { FloatArray(entry.classes.size) }
            tfl.run(inputBuffer, output)
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L

            val probs = output[0]
            var bestIdx = -1
            var bestScore = 0f
            for (i in probs.indices) {
                if (probs[i] > bestScore) {
                    bestScore = probs[i]
                    bestIdx = i
                }
            }

            val detections = if (bestIdx >= 0 && bestIdx < entry.classes.size) {
                val category = mapClassLabel(entry.classes[bestIdx])
                if (category != null) {
                    listOf(RawSignDetection(category, bestScore, normalizedCenterX = 0.5f))
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val confirmed = temporalTracker.processFrame(
                detections = detections,
                modelVersion = entry.version,
                nowMillis = timestampMillis,
            )

            _state.value = _state.value.copy(
                lastInferenceLatencyMs = latencyMs,
                lastObservation = confirmed ?: _state.value.lastObservation,
            )

            if (confirmed != null) {
                onConfirmedSign(confirmed)
            }
        } finally {
            imageProxy.close()
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        temporalTracker.reset()
    }

    private fun loadAssetFile(assetPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetPath)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun mapClassLabel(label: String): TrafficSignCategory? =
        TrafficSignCategory.entries.firstOrNull { it.name == label }
}
