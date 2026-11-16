package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.TrafficSignObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TrackedRoadObject(
    val trackingId: Int,
    val label: String,
    val confidence: Float,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
)

data class VisionPipelineState(
    val modelLoaded: Boolean = false,
    val modelVersion: String = "mlkit-offline-1.0",
    val validationStatus: String = "On-Device Offline",
    val lastObservation: TrafficSignObservation? = null,
    val trackedObjects: List<TrackedRoadObject> = emptyList(),
    val lastInferenceLatencyMs: Long = 0L,
    val statusMessage: String = "Initializing offline vision...",
)

@Singleton
class RoadVisionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val temporalTracker: SignTemporalTracker,
) {
    private val _state = MutableStateFlow(VisionPipelineState())
    val state: StateFlow<VisionPipelineState> = _state.asStateFlow()

    private var objectDetector: ObjectDetector? = null
    private var textRecognizer: TextRecognizer? = null

    fun initializeIfNeeded() {
        if (objectDetector != null && textRecognizer != null) return
        runCatching {
            val objOptions = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            objectDetector = ObjectDetection.getClient(objOptions)
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            _state.value = _state.value.copy(
                modelLoaded = true,
                modelVersion = "mlkit-offline-1.0",
                validationStatus = "On-Device Offline",
                statusMessage = "Offline object tracking & sign vision ready",
            )
        }.onFailure { err ->
            _state.value = _state.value.copy(
                modelLoaded = false,
                statusMessage = "Vision init failed: ${err.localizedMessage ?: "error"}",
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
            initializeIfNeeded()
            val detector = objectDetector
            val recognizer = textRecognizer
            if (detector == null || recognizer == null) return

            val startNs = System.nanoTime()
            val rawBitmap = imageProxy.toBitmap()
            val bitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)

            val detectedObjects = runCatching {
                Tasks.await(detector.process(inputImage), 450, TimeUnit.MILLISECONDS)
            }.getOrDefault(emptyList())

            val tracked = detectedObjects.mapNotNull { obj ->
                val box = obj.boundingBox
                val bestLabel = obj.labels.maxByOrNull { it.confidence }
                val labelText = bestLabel?.text?.takeIf { it.isNotBlank() } ?: "Object"
                TrackedRoadObject(
                    trackingId = obj.trackingId ?: -1,
                    label = labelText,
                    confidence = bestLabel?.confidence ?: 0.7f,
                    normalizedLeft = (box.left / w).coerceIn(0f, 1f),
                    normalizedTop = (box.top / h).coerceIn(0f, 1f),
                    normalizedRight = (box.right / w).coerceIn(0f, 1f),
                    normalizedBottom = (box.bottom / h).coerceIn(0f, 1f),
                )
            }

            val textResult = runCatching {
                Tasks.await(recognizer.process(inputImage), 450, TimeUnit.MILLISECONDS)
            }.getOrNull()

            val signCandidates = mutableListOf<RawSignDetection>()
            if (textResult != null) {
                for (block in textResult.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val hasBorder = SignCandidateParser.hasSignBorderColor(bitmap, box)
                    val centerX = box.centerX() / w
                    SignCandidateParser.parseSignFromText(block.text, hasBorder, centerX)?.let {
                        signCandidates.add(it)
                    }
                }
            }

            val confirmed = temporalTracker.processFrame(
                detections = signCandidates,
                modelVersion = _state.value.modelVersion,
                nowMillis = timestampMillis,
            )

            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            _state.value = _state.value.copy(
                trackedObjects = tracked,
                lastInferenceLatencyMs = latencyMs,
                lastObservation = confirmed ?: _state.value.lastObservation,
                statusMessage = "Tracking ${tracked.size} object(s) • Offline",
            )

            if (confirmed != null) {
                onConfirmedSign(confirmed)
            }
        } finally {
            imageProxy.close()
        }
    }

    fun release() {
        runCatching { objectDetector?.close() }
        runCatching { textRecognizer?.close() }
        objectDetector = null
        textRecognizer = null
        temporalTracker.reset()
    }
}
