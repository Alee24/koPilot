package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.common.model.TrafficSignCategory
import ke.co.kopilot.core.common.model.TrafficSignObservation
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.core.security.SecureTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
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
    val timestampMillis: Long = 0L,
)

data class VisionPipelineState(
    val modelLoaded: Boolean = false,
    val modelVersion: String = "mlkit-offline-1.0",
    val activeAccelerator: String = "CPU",
    val validationStatus: String = "On-Device Offline",
    val supportedSignClasses: List<String> = emptyList(),
    val detectedClassCounts: Map<String, Int> = emptyMap(),
    val recentSignHistory: List<TrafficSignObservation> = emptyList(),
    val lastObservation: TrafficSignObservation? = null,
    val lastSpeedLimitKmh: Int? = null,
    val lastDetectedPlate: String? = null,
    val lastPlateObservation: PlateObservation? = null,
    val redBrakeLightsAhead: Boolean = false,
    val trafficLightSignal: TrafficLightSignal = TrafficLightSignal.NONE,
    val isDarkScene: Boolean = false,
    val averageLuminance: Float = 128f,
    val recommendedZoomRatio: Float = 1.2f,
    val trackedObjects: List<TrackedRoadObject> = emptyList(),
    val signRoiBoxes: List<TrackedRoadObject> = emptyList(),
    val brakeLightBoxes: List<BrakeLightSpot> = emptyList(),
    val laneOverlay: LaneOverlayState = LaneOverlayState(),
    val lastInferenceLatencyMs: Long = 0L,
    val recentErrors: List<String> = emptyList(),
    val statusMessage: String = "Initializing offline vision...",
)

@Singleton
class RoadVisionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val temporalTracker: SignTemporalTracker,
    val signCatalog: LocalRoadSignCatalog,
    private val liteRtClassifier: LiteRtSignClassifier,
    private val liteRtYoloSignDetector: LiteRtYoloSignDetector,
    private val liteRtObjectDetector: LiteRtObjectDetector,
    private val multiObjectTracker: MultiObjectTracker,
    val platePipeline: PlateRecognitionPipeline,
    private val connectivityObserver: ConnectivityObserver,
    private val tokenStore: SecureTokenStore,
    private val okHttpClient: OkHttpClient,
    dispatchers: DispatcherProvider,
) {
    private val _state = MutableStateFlow(VisionPipelineState())
    val state: StateFlow<VisionPipelineState> = _state.asStateFlow()

    private val bgScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val json = Json { ignoreUnknownKeys = true }
    private var objectDetector: ObjectDetector? = null
    private var textRecognizer: TextRecognizer? = null
    private var brakeLightStreak = 0
    private var darkStreak = 0
    private var lastTrafficSignal: TrafficLightSignal = TrafficLightSignal.NONE
    private var trafficSignalStreak = 0
    private var lastAutoZoomAdjustMs = 0L
    private var frameCounter = 0
    private var lastCloudBoostMs = 0L

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
            liteRtYoloSignDetector.ensureInitialized()
            liteRtClassifier.ensureInitialized()
            val modeLabel = when {
                liteRtYoloSignDetector.isAvailable -> "Offline LiteRT YOLO11n (${liteRtYoloSignDetector.activeAccelerator})"
                liteRtClassifier.isAvailable -> "On-Device Hybrid (OCR+CV+LiteRT)"
                else -> "On-Device Hybrid (OCR+CV)"
            }

            _state.value = _state.value.copy(
                modelLoaded = true,
                modelVersion = if (liteRtYoloSignDetector.isAvailable) liteRtYoloSignDetector.modelVersion else "mlkit-cv-hybrid-2.0",
                activeAccelerator = liteRtYoloSignDetector.activeAccelerator,
                supportedSignClasses = liteRtYoloSignDetector.supportedLabels,
                validationStatus = modeLabel,
                statusMessage = "$modeLabel ready",
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
            frameCounter += 1

            // Extract salient sign ROIs in < 2ms and run Object Detection, Full-Frame OCR, and top-1 upscaled ROI OCR in parallel
            val signRois = SignRoiExtractor.extractSignRois(bitmap, maxRois = 2)
            val objTask: Task<List<DetectedObject>> = detector.process(inputImage)
            val topRoi = signRois.firstOrNull()
            val topRoiTask: Task<Text>? = topRoi?.let { roi ->
                val bmp = if (roi.bitmap.width < 180 || roi.bitmap.height < 180) {
                    Bitmap.createScaledBitmap(
                        roi.bitmap,
                        (roi.bitmap.width * 2).coerceAtLeast(180),
                        (roi.bitmap.height * 2).coerceAtLeast(180),
                        true,
                    )
                } else {
                    roi.bitmap
                }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
            }
            val fullOcrTask: Task<Text> = recognizer.process(inputImage)

            val allTasks = buildList<Task<*>> {
                add(objTask)
                if (topRoiTask != null) add(topRoiTask)
                add(fullOcrTask)
            }
            runCatching { Tasks.await(Tasks.whenAllComplete(allTasks), 750, TimeUnit.MILLISECONDS) }

            val detectedObjects = if (objTask.isSuccessful) objTask.result.orEmpty() else emptyList()

            val rawObjects = if (liteRtObjectDetector.isAvailable) {
                liteRtObjectDetector.detect(bitmap)
            } else {
                detectedObjects.mapNotNull { obj ->
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
                    RawObjectDetection(
                        label = roadLabel,
                        confidence = bestLabel?.confidence ?: 0.7f,
                        normalizedLeft = nLeft,
                        normalizedTop = nTop,
                        normalizedRight = nRight,
                        normalizedBottom = nBottom,
                    )
                }
            }
            val lighting = FrontRoadAnalyzer.analyzeLightingAndLights(bitmap)
            val combinedObjects = if (rawObjects.isEmpty() && lighting.brakeLightBoxes.isNotEmpty()) {
                val minL = lighting.brakeLightBoxes.minOf { it.normLeft }
                val minT = lighting.brakeLightBoxes.minOf { it.normTop }
                val maxR = lighting.brakeLightBoxes.maxOf { it.normRight }
                val maxB = lighting.brakeLightBoxes.maxOf { it.normBottom }
                listOf(
                    RawObjectDetection(
                        label = "Car (Braking)",
                        confidence = 0.85f,
                        normalizedLeft = (minL - 0.06f).coerceIn(0f, 1f),
                        normalizedTop = (minT - 0.16f).coerceIn(0f, 1f),
                        normalizedRight = (maxR + 0.06f).coerceIn(0f, 1f),
                        normalizedBottom = (maxB + 0.08f).coerceIn(0f, 1f),
                    ),
                )
            } else {
                rawObjects
            }
            val tracked = multiObjectTracker.update(combinedObjects, timestampMillis)
            brakeLightStreak = if (lighting.redBrakeLightsAhead) brakeLightStreak + 1 else 0
            darkStreak = if (lighting.isDark) darkStreak + 1 else 0
            if (lighting.trafficLightSignal != TrafficLightSignal.NONE && lighting.trafficLightSignal == lastTrafficSignal) {
                trafficSignalStreak += 1
            } else {
                lastTrafficSignal = lighting.trafficLightSignal
                trafficSignalStreak = if (lighting.trafficLightSignal != TrafficLightSignal.NONE) 1 else 0
            }
            val confirmedTrafficLight = if (trafficSignalStreak >= 2) lighting.trafficLightSignal else TrafficLightSignal.NONE

            val signCandidates = mutableListOf<RawSignDetection>()
            var detectedPlate: String? = null
            var confirmedPlateObs: PlateObservation? = null

            if (topRoi != null && topRoiTask != null && topRoiTask.isSuccessful) {
                val roiCenterX = (topRoi.normLeft + topRoi.normRight) / 2f
                val fullRoiRect = android.graphics.Rect(0, 0, topRoi.bitmap.width - 1, topRoi.bitmap.height - 1)
                val whiteInterior = SignCandidateParser.hasWhiteInterior(topRoi.bitmap, fullRoiRect)
                for (block in topRoiTask.result.textBlocks) {
                    val blockDet = SignCandidateParser.parseSignFromText(block.text, true, roiCenterX, whiteInterior)
                    if (blockDet != null) {
                        signCandidates.add(blockDet)
                    } else {
                        for (line in block.lines) {
                            val lineDet = SignCandidateParser.parseSignFromText(line.text, true, roiCenterX, whiteInterior)
                            if (lineDet != null) {
                                signCandidates.add(lineDet)
                            } else {
                                for (el in line.elements) {
                                    SignCandidateParser.parseSignFromText(el.text, true, roiCenterX, whiteInterior)?.let {
                                        signCandidates.add(it)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (fullOcrTask.isSuccessful) {
                for (block in fullOcrTask.result.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val normTop = box.top / h
                    if (normTop <= 0.92f) {
                        val hasBorder = SignCandidateParser.hasSignBorderColor(bitmap, box)
                        val whiteInterior = SignCandidateParser.hasWhiteInterior(bitmap, box)
                        val centerX = box.centerX() / w
                        val blockDet = SignCandidateParser.parseSignFromText(block.text, hasBorder, centerX, whiteInterior)
                        if (blockDet != null) {
                            signCandidates.add(blockDet)
                        } else {
                            for (line in block.lines) {
                                val lineDet = SignCandidateParser.parseSignFromText(line.text, hasBorder, centerX, whiteInterior)
                                if (lineDet != null) {
                                    signCandidates.add(lineDet)
                                } else {
                                    for (el in line.elements) {
                                        SignCandidateParser.parseSignFromText(el.text, hasBorder, centerX, whiteInterior)?.let {
                                            signCandidates.add(it)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (confirmedPlateObs == null) {
                        platePipeline.evaluateOcrBlock(bitmap, block, timestampMillis)?.let { obs ->
                            confirmedPlateObs = obs
                            detectedPlate = obs.maskedPlate
                        }
                    }
                }
            }

            // Primary Offline AI Road-Sign Detector (LiteRT YOLO11n)
            val yoloSignDetections = if (liteRtYoloSignDetector.isAvailable) {
                liteRtYoloSignDetector.detect(bitmap)
            } else {
                emptyList()
            }
            for (d in yoloSignDetections) {
                signCandidates.add(
                    RawSignDetection(
                        category = d.category,
                        confidence = d.confidence,
                        normalizedCenterX = (d.normalizedLeft + d.normalizedRight) / 2f,
                    ),
                )
            }

            // Option 2: Two-stage LiteRT TFLite classification on localized sign crops (if model is bundled)
            if (liteRtClassifier.isAvailable) {
                for (roi in signRois) {
                    val centerX = (roi.normLeft + roi.normRight) / 2f
                    liteRtClassifier.classifyCrop(roi.bitmap, centerX)?.let { signCandidates.add(it) }
                }
                if (signCandidates.isEmpty()) {
                    for (obj in detectedObjects.take(2)) {
                        val box = obj.boundingBox
                        val left = box.left.coerceIn(0, bitmap.width - 1)
                        val top = box.top.coerceIn(0, bitmap.height - 1)
                        val right = box.right.coerceIn(left + 1, bitmap.width)
                        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
                        if (top / h <= 0.75f && (right - left) >= 32 && (bottom - top) >= 32) {
                            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                            liteRtClassifier.classifyCrop(crop, box.centerX() / w)?.let { signCandidates.add(it) }
                        }
                    }
                }
            }

            // Option 3: Zero-model CV shape + color + interior symbol geometry when OCR/LiteRT found no text sign
            if (signCandidates.isEmpty()) {
                for (roi in signRois) {
                    OfflineSymbolSignDetector.classifyCrop(roi)?.let { signCandidates.add(it) }
                }
            }

            // Optional fast online symbol boost: if local OCR found nothing in a sign ROI and OpenAI key + internet are active,
            // send only the tiny cropped ROI (~5 KB) asynchronously to identify symbol-only signs (bends, bumps, roundabouts).
            if (signCandidates.isEmpty() && signRois.isNotEmpty() &&
                (timestampMillis - lastCloudBoostMs) >= 3_500L &&
                connectivityObserver.isCurrentlyOnline()
            ) {
                val apiKey = tokenStore.getOpenAiApiKey()
                if (apiKey != null) {
                    lastCloudBoostMs = timestampMillis
                    val topRoi = signRois.first()
                    val roiCopy = topRoi.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val centerX = (topRoi.normLeft + topRoi.normRight) / 2f
                    bgScope.launch {
                        classifySymbolRoiOnline(apiKey, roiCopy, centerX, onConfirmedSign)
                    }
                }
            }

            val yoloOverlayBoxes = yoloSignDetections.mapIndexed { idx, d ->
                TrackedRoadObject(
                    trackingId = idx + 1,
                    label = "${d.category.displayName} ${(d.confidence * 100).toInt()}%",
                    confidence = d.confidence,
                    normalizedLeft = d.normalizedLeft,
                    normalizedTop = d.normalizedTop,
                    normalizedRight = d.normalizedRight,
                    normalizedBottom = d.normalizedBottom,
                    timestampMillis = timestampMillis,
                )
            }
            val roiOverlayBoxes = yoloOverlayBoxes + signRois.mapIndexed { idx, r ->
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

            val laneState = LaneDetector.detectLanes(bitmap)
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            val nextSpeedLimit = if (confirmed != null && !confirmed.isUncertain && confirmed.category.speedLimitKmh != null) {
                confirmed.category.speedLimitKmh
            } else {
                _state.value.lastSpeedLimitKmh
            }
            val prevPlateObs = _state.value.lastPlateObservation
            val activePlateObs = confirmedPlateObs ?: prevPlateObs?.takeIf { (timestampMillis - it.timestampMillis) <= 30_000L }
            val updatedCounts = _state.value.detectedClassCounts.toMutableMap()
            for (d in yoloSignDetections) {
                val key = d.label
                updatedCounts[key] = (updatedCounts[key] ?: 0) + 1
            }
            if (confirmed != null && !confirmed.isUncertain) {
                val key = confirmed.category.name
                updatedCounts[key] = (updatedCounts[key] ?: 0) + 1
            }
            val updatedHistory = if (confirmed != null) {
                (listOf(confirmed) + _state.value.recentSignHistory).take(15)
            } else {
                _state.value.recentSignHistory
            }
            _state.value = _state.value.copy(
                trackedObjects = tracked,
                signRoiBoxes = roiOverlayBoxes,
                brakeLightBoxes = lighting.brakeLightBoxes,
                laneOverlay = laneState,
                lastInferenceLatencyMs = latencyMs,
                detectedClassCounts = updatedCounts,
                recentSignHistory = updatedHistory,
                lastObservation = confirmed ?: _state.value.lastObservation,
                lastSpeedLimitKmh = nextSpeedLimit,
                lastDetectedPlate = activePlateObs?.maskedPlate,
                lastPlateObservation = activePlateObs,
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

    private fun classifySymbolRoiOnline(
        apiKey: String,
        roiBitmap: Bitmap,
        centerX: Float,
        onConfirmedSign: (TrafficSignObservation) -> Unit,
    ) {
        runCatching {
            val scaled = Bitmap.createScaledBitmap(roiBitmap, 160, 160, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            val classes = TrafficSignCategory.entries.joinToString(",") { it.name }
            val payload = """
                {
                  "model": "gpt-4o-mini",
                  "max_tokens": 20,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "If this is a road sign, reply with ONLY one enum from [$classes], else NONE."},
                      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$b64", "detail": "low"}}
                    ]
                  }]
                }
            """.trimIndent()
            val req = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val raw = resp.body?.string().orEmpty()
                val reply = json.parseToJsonElement(raw).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content?.trim().orEmpty()
                val category = TrafficSignCategory.entries.firstOrNull { it.name == reply } ?: return
                val obs = temporalTracker.processFrame(
                    detections = listOf(RawSignDetection(category, 0.88f, centerX)),
                    modelVersion = "hybrid-online-1.0",
                    nowMillis = System.currentTimeMillis(),
                ) ?: return
                val nextLimit = obs.category.speedLimitKmh ?: _state.value.lastSpeedLimitKmh
                _state.value = _state.value.copy(
                    lastObservation = obs,
                    lastSpeedLimitKmh = nextLimit,
                )
                onConfirmedSign(obs)
            }
        }
    }
}
