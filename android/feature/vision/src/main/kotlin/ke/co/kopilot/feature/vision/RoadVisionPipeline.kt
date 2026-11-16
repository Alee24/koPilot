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
    val lastSpeedLimitKmh: Int? = null,
    val lastDetectedPlate: String? = null,
    val redBrakeLightsAhead: Boolean = false,
    val trafficLightSignal: TrafficLightSignal = TrafficLightSignal.NONE,
    val isDarkScene: Boolean = false,
    val averageLuminance: Float = 128f,
    val recommendedZoomRatio: Float = 1.2f,
    val trackedObjects: List<TrackedRoadObject> = emptyList(),
    val signRoiBoxes: List<TrackedRoadObject> = emptyList(),
    val lastInferenceLatencyMs: Long = 0L,
    val statusMessage: String = "Initializing offline vision...",
)

@Singleton
class RoadVisionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val temporalTracker: SignTemporalTracker,
    val signCatalog: LocalRoadSignCatalog,
) {
    private val _state = MutableStateFlow(VisionPipelineState())
    val state: StateFlow<VisionPipelineState> = _state.asStateFlow()

    private var objectDetector: ObjectDetector? = null
    private var textRecognizer: TextRecognizer? = null
    private var brakeLightStreak = 0
    private var darkStreak = 0
    private var lastTrafficSignal: TrafficLightSignal = TrafficLightSignal.NONE
    private var trafficSignalStreak = 0
    private var lastAutoZoomAdjustMs = 0L

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
                val rawLabel = bestLabel?.text?.takeIf { it.isNotBlank() } ?: "Object"
                val nLeft = (box.left / w).coerceIn(0f, 1f)
                val nTop = (box.top / h).coerceIn(0f, 1f)
                val nRight = (box.right / w).coerceIn(0f, 1f)
                val nBottom = (box.bottom / h).coerceIn(0f, 1f)
                val roadLabel = FrontRoadAnalyzer.classifyObjectByGeometry(
                    normLeft = nLeft,
                    normTop = nTop,
                    normRight = nRight,
                    normBottom = nBottom,
                    mlKitLabel = rawLabel,
                )
                TrackedRoadObject(
                    trackingId = obj.trackingId ?: -1,
                    label = roadLabel,
                    confidence = bestLabel?.confidence ?: 0.7f,
                    normalizedLeft = nLeft,
                    normalizedTop = nTop,
                    normalizedRight = nRight,
                    normalizedBottom = nBottom,
                )
            }

            val lighting = FrontRoadAnalyzer.analyzeLightingAndLights(bitmap)
            brakeLightStreak = if (lighting.redBrakeLightsAhead) brakeLightStreak + 1 else 0
            darkStreak = if (lighting.isDark) darkStreak + 1 else 0
            if (lighting.trafficLightSignal != TrafficLightSignal.NONE && lighting.trafficLightSignal == lastTrafficSignal) {
                trafficSignalStreak += 1
            } else {
                lastTrafficSignal = lighting.trafficLightSignal
                trafficSignalStreak = if (lighting.trafficLightSignal != TrafficLightSignal.NONE) 1 else 0
            }
            val confirmedTrafficLight = if (trafficSignalStreak >= 2) lighting.trafficLightSignal else TrafficLightSignal.NONE

            // Focus OCR on salient sign-colored ROIs first (instead of scanning the entire asphalt road)
            val signRois = SignRoiExtractor.extractSignRois(bitmap, maxRois = 3)
            val signCandidates = mutableListOf<RawSignDetection>()
            var detectedPlate: String? = null

            if (signRois.isNotEmpty()) {
                for (roi in signRois) {
                    val roiInput = InputImage.fromBitmap(roi.bitmap, 0)
                    val roiText = runCatching {
                        Tasks.await(recognizer.process(roiInput), 250, TimeUnit.MILLISECONDS)
                    }.getOrNull() ?: continue
                    val roiCenterX = (roi.normLeft + roi.normRight) / 2f
                    for (block in roiText.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val hasBorder = SignCandidateParser.hasSignBorderColor(roi.bitmap, box)
                        val whiteInterior = SignCandidateParser.hasWhiteInterior(roi.bitmap, box)
                        SignCandidateParser.parseSignFromText(block.text, hasBorder, roiCenterX, whiteInterior)?.let {
                            signCandidates.add(it)
                        }
                    }
                }
            }

            // Fallback / plate scan on full frame only if no sign found in ROIs or to catch lower-road plates
            if (signCandidates.isEmpty()) {
                val textResult = runCatching {
                    Tasks.await(recognizer.process(inputImage), 350, TimeUnit.MILLISECONDS)
                }.getOrNull()

                if (textResult != null) {
                    for (block in textResult.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val normTop = box.top / h
                        if (normTop <= 0.65f) {
                            val hasBorder = SignCandidateParser.hasSignBorderColor(bitmap, box)
                            val whiteInterior = SignCandidateParser.hasWhiteInterior(bitmap, box)
                            val centerX = box.centerX() / w
                            SignCandidateParser.parseSignFromText(block.text, hasBorder, centerX, whiteInterior)?.let {
                                signCandidates.add(it)
                            }
                        }
                        if (detectedPlate == null) {
                            detectedPlate = FrontRoadAnalyzer.extractKenyanPlate(block.text)
                        }
                    }
                }
            }

            val roiOverlayBoxes = signRois.mapIndexed { idx, r ->
                TrackedRoadObject(
                    trackingId = idx,
                    label = "Sign ROI",
                    confidence = 1f,
                    normalizedLeft = r.normLeft,
                    normalizedTop = r.normTop,
                    normalizedRight = r.normRight,
                    normalizedBottom = r.normBottom,
                )
            }

            val confirmed = temporalTracker.processFrame(
                detections = signCandidates,
                modelVersion = _state.value.modelVersion,
                nowMillis = timestampMillis,
            )

            // Auto-zoom controller: zoom out if sign candidate confidence < 60%; zoom in on small distant upper objects
            var nextZoom = _state.value.recommendedZoomRatio
            if ((timestampMillis - lastAutoZoomAdjustMs) >= 1200L) {
                val bestCandidate = signCandidates.maxByOrNull { it.confidence }
                val hasSmallDistantSignObject = tracked.any {
                    (it.normalizedRight - it.normalizedLeft) < 0.11f && it.normalizedTop < 0.48f
                }
                nextZoom = when {
                    bestCandidate != null && bestCandidate.confidence < SignTemporalTracker.SURE_CONFIDENCE_THRESHOLD -> {
                        lastAutoZoomAdjustMs = timestampMillis
                        (nextZoom - 0.3f).coerceAtLeast(1.0f)
                    }
                    bestCandidate == null && hasSmallDistantSignObject && nextZoom < 1.9f -> {
                        lastAutoZoomAdjustMs = timestampMillis
                        (nextZoom + 0.25f).coerceAtMost(2.0f)
                    }
                    else -> nextZoom
                }
            }

            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            val nextSpeedLimit = if (confirmed != null && !confirmed.isUncertain && confirmed.category.speedLimitKmh != null) {
                confirmed.category.speedLimitKmh
            } else {
                _state.value.lastSpeedLimitKmh
            }
            _state.value = _state.value.copy(
                trackedObjects = tracked,
                signRoiBoxes = roiOverlayBoxes,
                lastInferenceLatencyMs = latencyMs,
                lastObservation = confirmed ?: _state.value.lastObservation,
                lastSpeedLimitKmh = nextSpeedLimit,
                lastDetectedPlate = detectedPlate ?: _state.value.lastDetectedPlate,
                redBrakeLightsAhead = brakeLightStreak >= 2,
                trafficLightSignal = confirmedTrafficLight,
                isDarkScene = darkStreak >= 3,
                averageLuminance = lighting.averageLuminance,
                recommendedZoomRatio = nextZoom,
                statusMessage = "Tracking ${tracked.size} obj • ${signRois.size} Sign ROI • ${"%.1f".format(nextZoom)}x",
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
