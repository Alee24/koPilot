package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
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
import ke.co.kopilot.core.common.model.TrafficSignObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    val selectedBackend: LiteRtAcceleratorBackend = LiteRtAcceleratorBackend.AUTO_XNNPACK,
    val benchmarkResults: List<AcceleratorBenchmarkResult> = emptyList(),
    val performanceMetrics: VisionPerformanceMetrics = VisionPerformanceMetrics(),
    val validationStatus: String = "100% On-Device Offline",
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
    val recommendedZoomRatio: Float = 1.3f,
    val trackedObjects: List<TrackedRoadObject> = emptyList(),
    val signRoiBoxes: List<TrackedRoadObject> = emptyList(),
    val brakeLightBoxes: List<BrakeLightSpot> = emptyList(),
    val laneOverlay: LaneOverlayState = LaneOverlayState(),
    val lastInferenceLatencyMs: Long = 0L,
    val recentErrors: List<String> = emptyList(),
    val statusMessage: String = "Initializing offline vision...",
)

/**
 * 100% Offline On-Device Road Vision Pipeline with Intelligent Thermal & Latency Task Scheduling
 * and Zero-Allocation Reusable Frame/ROI Bitmap Buffers.
 *
 * Priority execution order:
 *   1. Road signs & speed limits (LiteRT YOLO11n + High-Density Sign ROI OCR + Shape/Symbol CV)
 *   2. Traffic-light housing & state detection (Vertical/Horizontal 3-aspect housing validation)
 *   3. Road object tracking & lane detection
 *   4. Vehicle number-plate detection & offline OCR (White & Yellow plate locator, deskew, contrast stretch)
 *
 * Zero cloud or internet dependencies.
 */
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
) {
    private val _state = MutableStateFlow(VisionPipelineState())
    val state: StateFlow<VisionPipelineState> = _state.asStateFlow()

    val taskScheduler = VisionTaskScheduler()

    private var objectDetector: ObjectDetector? = null
    private var textRecognizer: TextRecognizer? = null
    private var brakeLightStreak = 0
    private var darkStreak = 0
    private var lastTrafficSignal: TrafficLightSignal = TrafficLightSignal.NONE
    private var trafficSignalStreak = 0
    private var frameCounter = 0
    private val plateOcrBusy = AtomicBoolean(false)

    // Reusable preallocated frame rotation buffer to eliminate ~3.7 MB per-frame allocation
    private var reusableRotatedBitmap: Bitmap? = null
    private var reusableRotatedCanvas: Canvas? = null
    private val reusableRotateMatrix = Matrix()
    private val bitmapFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Reusable preallocated 3-slot Sign ROI composite strip (576 x 192 ARGB_8888)
    private var reusableRoiCompositeBitmap: Bitmap? = null
    private var reusableRoiCompositeCanvas: Canvas? = null
    private val reusableRoiDstRect = Rect()

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
                liteRtClassifier.isAvailable -> "Offline Hybrid (OCR+CV+LiteRT)"
                else -> "Offline Hybrid (OCR+CV)"
            }

            _state.value = _state.value.copy(
                modelLoaded = true,
                modelVersion = if (liteRtYoloSignDetector.isAvailable) liteRtYoloSignDetector.modelVersion else "mlkit-cv-offline-2.0",
                activeAccelerator = liteRtYoloSignDetector.activeAccelerator,
                selectedBackend = liteRtYoloSignDetector.selectedBackend,
                benchmarkResults = liteRtYoloSignDetector.lastBenchmarkResults,
                supportedSignClasses = liteRtYoloSignDetector.supportedLabels,
                validationStatus = "100% On-Device Offline • $modeLabel",
                statusMessage = "$modeLabel ready",
            )
        }.onFailure { err ->
            _state.value = _state.value.copy(
                modelLoaded = false,
                statusMessage = "Vision init failed: ${err.localizedMessage ?: "error"}",
            )
        }
    }

    fun selectAcceleratorBackend(backend: LiteRtAcceleratorBackend): Boolean {
        val ok = liteRtYoloSignDetector.setPreferredBackend(backend)
        if (ok) {
            val modeLabel = "Offline LiteRT YOLO11n (${liteRtYoloSignDetector.activeAccelerator})"
            _state.value = _state.value.copy(
                activeAccelerator = liteRtYoloSignDetector.activeAccelerator,
                selectedBackend = liteRtYoloSignDetector.selectedBackend,
                validationStatus = "100% On-Device Offline • $modeLabel",
                statusMessage = "$modeLabel active",
            )
        }
        return ok
    }

    fun runAcceleratorBenchmark(): List<AcceleratorBenchmarkResult> {
        val results = liteRtYoloSignDetector.runOnDeviceBenchmark(iterations = 6)
        val modeLabel = "Offline LiteRT YOLO11n (${liteRtYoloSignDetector.activeAccelerator})"
        _state.value = _state.value.copy(
            activeAccelerator = liteRtYoloSignDetector.activeAccelerator,
            selectedBackend = liteRtYoloSignDetector.selectedBackend,
            benchmarkResults = results,
            validationStatus = "100% On-Device Offline • $modeLabel",
            statusMessage = "Benchmark complete: ${liteRtYoloSignDetector.activeAccelerator} selected",
        )
        return results
    }

    fun analyzeFrame(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        timestampMillis: Long,
        thermalModeName: String = "NORMAL",
        onConfirmedSign: (TrafficSignObservation) -> Unit,
    ) {
        try {
            initializeIfNeeded()
            val detector = objectDetector
            val recognizer = textRecognizer
            if (detector == null || recognizer == null) return

            val startNs = System.nanoTime()
            val nowMillis = System.currentTimeMillis()
            val frameAgeMs = (nowMillis - timestampMillis).coerceAtLeast(0L)
            val thermalMode = VisionThermalMode.fromNameOrDefault(thermalModeName)

            val rawBitmap = imageProxy.toBitmap()
            val bitmap = obtainRotatedBitmapInPlace(rawBitmap, rotationDegrees)

            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            frameCounter += 1

            // =========================================================================
            // PRIORITY 1: ROAD SIGNS & SPEED LIMITS (Every Frame, Full Camera View)
            // =========================================================================
            val roiStartNs = System.nanoTime()
            val signRois = SignRoiExtractor.extractSignRois(bitmap, maxRois = 3)
            val roiComposite = buildSignRoiCompositeReusable(signRois)

            // Ask Intelligent AI Task Scheduler for this frame's execution plan
            val schedule = taskScheduler.decideSchedule(
                frameIndex = frameCounter,
                frameAgeMs = frameAgeMs,
                lastFrameLatencyMs = _state.value.lastInferenceLatencyMs,
                thermalMode = thermalMode,
                hasSignRois = signRois.isNotEmpty(),
            )

            val roiOcrTask: Task<Text>? = if (schedule.runSignRoiExtractionAndOcr && roiComposite != null) {
                recognizer.process(InputImage.fromBitmap(roiComposite.bitmap, 0))
            } else {
                null
            }

            // Submit scheduled ML Kit tasks asynchronously so LiteRT YOLO11n runs concurrently
            val needFullInputImage = schedule.runObjectDetection || schedule.runFullFrameOcr
            val inputImage: InputImage? = if (needFullInputImage) InputImage.fromBitmap(bitmap, 0) else null
            val objTask: Task<List<DetectedObject>>? = if (schedule.runObjectDetection && inputImage != null) {
                detector.process(inputImage)
            } else {
                null
            }
            val fullOcrTask: Task<Text>? = if (schedule.runFullFrameOcr && inputImage != null) {
                recognizer.process(inputImage)
            } else {
                null
            }

            // Run LiteRT YOLO11n Sign Detector on XNNPACK/NNAPI while ML Kit tasks execute in parallel
            var yoloElapsedMs: Long? = null
            val yoloSignDetections = if (schedule.runRoadSignYolo && liteRtYoloSignDetector.isAvailable) {
                val y0 = System.nanoTime()
                val dets = liteRtYoloSignDetector.detect(
                    bitmap = bitmap,
                    includeHorizonZoomPass = schedule.runYoloHorizonZoomPass,
                )
                yoloElapsedMs = ((System.nanoTime() - y0) / 1_000_000L).coerceAtLeast(1L)
                dets
            } else {
                emptyList()
            }

            val parallelAwaitStartNs = System.nanoTime()
            val criticalTasks = buildList<Task<*>> {
                if (objTask != null) add(objTask)
                if (roiOcrTask != null) add(roiOcrTask)
                if (fullOcrTask != null) add(fullOcrTask)
            }
            if (criticalTasks.isNotEmpty()) {
                runCatching { Tasks.await(Tasks.whenAllComplete(criticalTasks), 420, TimeUnit.MILLISECONDS) }
            }
            val parallelAwaitElapsedMs = ((System.nanoTime() - parallelAwaitStartNs) / 1_000_000L).coerceAtLeast(1L)

            var objectElapsedMs: Long? = null
            val tracked = if (schedule.runObjectDetection) {
                val o0 = System.nanoTime()
                val detectedObjects = if (objTask != null && objTask.isSuccessful) objTask.result.orEmpty() else emptyList()
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
                val updatedTracked = multiObjectTracker.update(rawObjects, timestampMillis)
                objectElapsedMs = (((System.nanoTime() - o0) / 1_000_000L) + (parallelAwaitElapsedMs / 2)).coerceAtLeast(1L)
                updatedTracked
            } else {
                // Keep recently tracked objects when Tier 3 is paced on intermediate frames, expiring stale boxes
                _state.value.trackedObjects.filter { (timestampMillis - it.timestampMillis) <= 3_500L }
            }

            // =========================================================================
            // PRIORITY 2: TRAFFIC-LIGHT DETECTION & SIGNAL STATE (Every Frame)
            // =========================================================================
            val tlStartNs = System.nanoTime()
            val lighting = FrontRoadAnalyzer.analyzeLightingAndLights(bitmap, tracked)
            val trafficLightElapsedMs = ((System.nanoTime() - tlStartNs) / 1_000_000L).coerceAtLeast(1L)

            brakeLightStreak = if (lighting.redBrakeLightsAhead) brakeLightStreak + 1 else 0
            darkStreak = if (lighting.isDark) darkStreak + 1 else 0
            if (lighting.trafficLightSignal != TrafficLightSignal.NONE &&
                lighting.trafficLightSignal != TrafficLightSignal.UNCERTAIN &&
                lighting.trafficLightSignal == lastTrafficSignal
            ) {
                trafficSignalStreak += 1
            } else {
                lastTrafficSignal = lighting.trafficLightSignal
                trafficSignalStreak = if (
                    lighting.trafficLightSignal != TrafficLightSignal.NONE &&
                    lighting.trafficLightSignal != TrafficLightSignal.UNCERTAIN
                ) 1 else 0
            }
            val confirmedTrafficLight = when {
                trafficSignalStreak >= 2 -> lighting.trafficLightSignal
                lighting.trafficLightSignal == TrafficLightSignal.UNCERTAIN -> TrafficLightSignal.UNCERTAIN
                else -> TrafficLightSignal.NONE
            }

            // Collect & Verify Road Sign Candidates (YOLO11n + Multi-ROI OCR + Full-Frame OCR + Symbol CV)
            val signCandidates = mutableListOf<RawSignDetection>()

            for (d in yoloSignDetections) {
                val centerX = (d.normalizedLeft + d.normalizedRight) * 0.5f
                val onVehicle = SignCandidateParser.isBoxInsideDetectedVehicle(
                    d.normalizedLeft,
                    d.normalizedTop,
                    d.normalizedRight,
                    d.normalizedBottom,
                    tracked,
                )
                if (!onVehicle) {
                    signCandidates.add(
                        RawSignDetection(
                            category = d.category,
                            confidence = d.confidence,
                            normalizedCenterX = centerX,
                        ),
                    )
                }
            }

            // Parse Multi-ROI Composite OCR with actual ROI perimeter border & white interior verification
            val roiParseStartNs = System.nanoTime()
            if (roiComposite != null && roiOcrTask != null && roiOcrTask.isSuccessful) {
                for (block in roiOcrTask.result.textBlocks) {
                    val bBox = block.boundingBox ?: continue
                    val slotIdx = (bBox.centerX() / roiComposite.slotWidthPx).coerceIn(0, roiComposite.rois.lastIndex)
                    val matchedRoi = roiComposite.rois[slotIdx]
                    val roiCenterX = (matchedRoi.normLeft + matchedRoi.normRight) * 0.5f
                    val onVehicle = SignCandidateParser.isBoxInsideDetectedVehicle(
                        matchedRoi.normLeft,
                        matchedRoi.normTop,
                        matchedRoi.normRight,
                        matchedRoi.normBottom,
                        tracked,
                    )
                    val fullRoiRect = Rect(0, 0, matchedRoi.bitmap.width - 1, matchedRoi.bitmap.height - 1)
                    val hasVerifiedBorder = SignCandidateParser.hasRoiPerimeterSignColor(matchedRoi.bitmap)
                    val whiteInterior = SignCandidateParser.hasWhiteInterior(matchedRoi.bitmap, fullRoiRect)
                    parseTextBlockIntoSignCandidates(
                        block = block,
                        hasBorder = hasVerifiedBorder,
                        centerX = roiCenterX,
                        whiteInterior = whiteInterior,
                        isOnVehicle = onVehicle,
                        out = signCandidates,
                    )
                }
            }

            var confirmedPlateObs: PlateObservation? = null

            // Parse Full-Frame OCR for Road Signs & Registration Plates
            var fullOcrElapsedMs: Long? = null
            if (fullOcrTask != null && fullOcrTask.isSuccessful) {
                val f0 = System.nanoTime()
                for (block in fullOcrTask.result.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val nLeft = (box.left / w).coerceIn(0f, 1f)
                    val nTop = (box.top / h).coerceIn(0f, 1f)
                    val nRight = (box.right / w).coerceIn(0f, 1f)
                    val nBottom = (box.bottom / h).coerceIn(0f, 1f)
                    if (nTop <= 0.92f) {
                        val hasBorder = SignCandidateParser.hasSignBorderColor(bitmap, box)
                        val whiteInterior = SignCandidateParser.hasWhiteInterior(bitmap, box)
                        val centerX = (nLeft + nRight) * 0.5f
                        val onVehicle = SignCandidateParser.isBoxInsideDetectedVehicle(nLeft, nTop, nRight, nBottom, tracked)
                        parseTextBlockIntoSignCandidates(
                            block = block,
                            hasBorder = hasBorder,
                            centerX = centerX,
                            whiteInterior = whiteInterior,
                            isOnVehicle = onVehicle,
                            out = signCandidates,
                        )
                    }
                    if (confirmedPlateObs == null) {
                        platePipeline.evaluateOcrBlock(bitmap, block, timestampMillis)?.let { obs ->
                            confirmedPlateObs = obs
                        }
                    }
                }
                fullOcrElapsedMs = (((System.nanoTime() - f0) / 1_000_000L) + (parallelAwaitElapsedMs / 2)).coerceAtLeast(1L)
            }

            // Option 2: Two-stage LiteRT TFLite crop classification (when bundled)
            if (liteRtClassifier.isAvailable) {
                for (roi in signRois) {
                    val centerX = (roi.normLeft + roi.normRight) * 0.5f
                    liteRtClassifier.classifyCrop(roi.bitmap, centerX)?.let { signCandidates.add(it) }
                }
            }

            // Option 3: Pure-CV geometric shape + interior symbol analyzer on all extracted Sign ROIs
            if (schedule.runSignSymbolCv) {
                for (roi in signRois) {
                    val onVehicle = SignCandidateParser.isBoxInsideDetectedVehicle(
                        roi.normLeft,
                        roi.normTop,
                        roi.normRight,
                        roi.normBottom,
                        tracked,
                    )
                    if (!onVehicle) {
                        OfflineSymbolSignDetector.classifyCrop(roi)?.let { sym ->
                            if (signCandidates.none { it.category == sym.category }) {
                                signCandidates.add(sym)
                            }
                        }
                    }
                }
            }
            val roiOcrElapsedMs = (((System.nanoTime() - roiStartNs - (parallelAwaitStartNs - roiStartNs).coerceAtLeast(0L)) +
                (System.nanoTime() - roiParseStartNs)) / 1_000_000L + (parallelAwaitElapsedMs / 3)).coerceAtLeast(1L)

            // =========================================================================
            // PRIORITY 4: DEDICATED WHITE & YELLOW NUMBER-PLATE REGION OCR (Scheduled)
            // =========================================================================
            var plateOcrElapsedMs: Long? = null
            val elapsedSoFarMs = (System.nanoTime() - startNs) / 1_000_000L
            if (schedule.runDedicatedPlateOcr &&
                confirmedPlateObs == null &&
                elapsedSoFarMs < 180L &&
                plateOcrBusy.compareAndSet(false, true)
            ) {
                val p0 = System.nanoTime()
                try {
                    val plateRegions = platePipeline.locateAndPreparePlateCandidates(bitmap, tracked, maxCandidates = 1)
                    val topPlateCandidate = plateRegions.firstOrNull()
                    if (topPlateCandidate != null) {
                        val plateTask = recognizer.process(InputImage.fromBitmap(topPlateCandidate.crop, 0))
                        runCatching { Tasks.await(plateTask, 170, TimeUnit.MILLISECONDS) }
                        if (plateTask.isSuccessful) {
                            for (block in plateTask.result.textBlocks) {
                                val validated = KenyanPlateValidator.validateFormat(block.text)
                                    ?: block.lines.firstNotNullOfOrNull { KenyanPlateValidator.validateFormat(it.text) }
                                if (validated != null) {
                                    confirmedPlateObs = platePipeline.recordAndConfirmPlateReading(
                                        normalized = validated.first,
                                        formatName = validated.second,
                                        ocrConfidence = 0.82f,
                                        qualityScore = (topPlateCandidate.edgeScore / 45f).coerceIn(0.5f, 1.0f),
                                        surfaceColor = topPlateCandidate.surfaceColor,
                                        timestampMillis = timestampMillis,
                                    )
                                    if (confirmedPlateObs != null) break
                                }
                            }
                        }
                    }
                } finally {
                    plateOcrBusy.set(false)
                }
                plateOcrElapsedMs = ((System.nanoTime() - p0) / 1_000_000L).coerceAtLeast(1L)
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
                    confidence = r.signScore,
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

            // =========================================================================
            // AUXILIARY LANE DETECTION & TELEMETRY STATE PUBLISH
            // =========================================================================
            var laneElapsedMs: Long? = null
            val laneState = if (schedule.runLaneDetection) {
                val l0 = System.nanoTime()
                val ls = LaneDetector.detectLanes(bitmap)
                laneElapsedMs = ((System.nanoTime() - l0) / 1_000_000L).coerceAtLeast(1L)
                ls
            } else {
                _state.value.laneOverlay
            }

            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val perfMetrics = taskScheduler.recordFrameTimings(
                nowMillis = timestampMillis,
                thermalMode = thermalMode,
                totalFrameMs = latencyMs,
                yoloMs = yoloElapsedMs,
                roiOcrMs = roiOcrElapsedMs,
                fullOcrMs = fullOcrElapsedMs,
                plateOcrMs = plateOcrElapsedMs,
                objectDetectMs = objectElapsedMs,
                trafficLightMs = trafficLightElapsedMs,
                laneMs = laneElapsedMs,
                schedule = schedule,
            )

            // Expire stale detections when past thermal-state validity window so outdated signs are never shown as current
            val nonStalePrevObservation = _state.value.lastObservation?.takeIf { prev ->
                (timestampMillis - prev.timestampMillis) <= schedule.maxDetectionValidityMs
            }
            val activeObservation = confirmed ?: nonStalePrevObservation

            val nextSpeedLimit = when {
                confirmed != null && !confirmed.isUncertain && confirmed.category.speedLimitKmh != null ->
                    confirmed.category.speedLimitKmh
                thermalMode == VisionThermalMode.CRITICAL && activeObservation == null -> null
                else -> _state.value.lastSpeedLimitKmh
            }
            val prevPlateObs = _state.value.lastPlateObservation
            val activePlateObs = confirmedPlateObs ?: prevPlateObs?.takeIf {
                (timestampMillis - it.timestampMillis) <= schedule.maxDetectionValidityMs
            }
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
                activeAccelerator = liteRtYoloSignDetector.activeAccelerator,
                selectedBackend = liteRtYoloSignDetector.selectedBackend,
                benchmarkResults = liteRtYoloSignDetector.lastBenchmarkResults,
                performanceMetrics = perfMetrics,
                trackedObjects = tracked,
                signRoiBoxes = roiOverlayBoxes,
                brakeLightBoxes = lighting.brakeLightBoxes,
                laneOverlay = laneState,
                lastInferenceLatencyMs = latencyMs,
                detectedClassCounts = updatedCounts,
                recentSignHistory = updatedHistory,
                lastObservation = activeObservation,
                lastSpeedLimitKmh = nextSpeedLimit,
                lastDetectedPlate = activePlateObs?.maskedPlate,
                lastPlateObservation = activePlateObs,
                redBrakeLightsAhead = brakeLightStreak >= 2,
                trafficLightSignal = confirmedTrafficLight,
                isDarkScene = darkStreak >= 3,
                averageLuminance = lighting.averageLuminance,
                statusMessage = "Offline Vision (${thermalMode.displayName}) • ${tracked.size} obj • ${signRois.size} Sign ROI • ${latencyMs}ms",
            )

            if (confirmed != null) {
                onConfirmedSign(confirmed)
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Rotates [rawBitmap] into a preallocated reusable [Bitmap] via [Canvas] + [Matrix]
     * to avoid allocating a new ~3.7 MB full-frame bitmap on every camera frame.
     */
    private fun obtainRotatedBitmapInPlace(rawBitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        if (normRot == 0) return rawBitmap

        val swapDims = (normRot == 90 || normRot == 270)
        val outW = if (swapDims) rawBitmap.height else rawBitmap.width
        val outH = if (swapDims) rawBitmap.width else rawBitmap.height

        var targetBmp = reusableRotatedBitmap
        var targetCanvas = reusableRotatedCanvas
        if (targetBmp == null || targetCanvas == null || targetBmp.width != outW || targetBmp.height != outH) {
            targetBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            targetCanvas = Canvas(targetBmp)
            reusableRotatedBitmap = targetBmp
            reusableRotatedCanvas = targetCanvas
        }

        reusableRotateMatrix.reset()
        reusableRotateMatrix.postTranslate(-rawBitmap.width * 0.5f, -rawBitmap.height * 0.5f)
        reusableRotateMatrix.postRotate(normRot.toFloat())
        reusableRotateMatrix.postTranslate(outW * 0.5f, outH * 0.5f)

        targetCanvas.drawBitmap(rawBitmap, reusableRotateMatrix, bitmapFilterPaint)
        return targetBmp
    }

    private data class SignRoiComposite(
        val bitmap: Bitmap,
        val rois: List<SignRoiCrop>,
        val slotWidthPx: Int,
    )

    /**
     * Composites up to 3 candidate Sign ROIs side-by-side into a single preallocated 576x192 strip
     * so ML Kit TextRecognizer inspects all top sign candidates in one fast offline pass with zero GC churn.
     */
    private fun buildSignRoiCompositeReusable(rois: List<SignRoiCrop>): SignRoiComposite? {
        if (rois.isEmpty()) return null
        val slotSize = 192
        val maxSlots = 3
        val stripWidth = slotSize * maxSlots
        val count = rois.size.coerceAtMost(maxSlots)
        val subset = rois.take(count)

        var outBmp = reusableRoiCompositeBitmap
        var canvas = reusableRoiCompositeCanvas
        if (outBmp == null || canvas == null || outBmp.width != stripWidth || outBmp.height != slotSize) {
            outBmp = Bitmap.createBitmap(stripWidth, slotSize, Bitmap.Config.ARGB_8888)
            canvas = Canvas(outBmp)
            reusableRoiCompositeBitmap = outBmp
            reusableRoiCompositeCanvas = canvas
        }

        canvas.drawColor(Color.BLACK)

        subset.forEachIndexed { index, roi ->
            val src = roi.bitmap
            val scale = minOf(
                (slotSize - 16).toFloat() / src.width.coerceAtLeast(1),
                (slotSize - 16).toFloat() / src.height.coerceAtLeast(1),
            )
            val dw = (src.width * scale).toInt().coerceAtLeast(16)
            val dh = (src.height * scale).toInt().coerceAtLeast(16)
            val left = index * slotSize + (slotSize - dw) / 2
            val top = (slotSize - dh) / 2
            reusableRoiDstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(src, null, reusableRoiDstRect, bitmapFilterPaint)
        }
        return SignRoiComposite(outBmp, subset, slotSize)
    }

    private fun parseTextBlockIntoSignCandidates(
        block: Text.TextBlock,
        hasBorder: Boolean,
        centerX: Float,
        whiteInterior: Boolean,
        isOnVehicle: Boolean,
        out: MutableList<RawSignDetection>,
    ) {
        val blockDet = SignCandidateParser.parseSignFromText(block.text, hasBorder, centerX, whiteInterior, isOnVehicle)
        if (blockDet != null) {
            out.add(blockDet)
        } else {
            for (line in block.lines) {
                val lineDet = SignCandidateParser.parseSignFromText(line.text, hasBorder, centerX, whiteInterior, isOnVehicle)
                if (lineDet != null) {
                    out.add(lineDet)
                } else {
                    for (el in line.elements) {
                        SignCandidateParser.parseSignFromText(el.text, hasBorder, centerX, whiteInterior, isOnVehicle)?.let {
                            out.add(it)
                        }
                    }
                }
            }
        }
    }

    fun release() {
        runCatching { objectDetector?.close() }
        runCatching { textRecognizer?.close() }
        objectDetector = null
        textRecognizer = null
        temporalTracker.reset()
        platePipeline.reset()
    }
}
