package ke.co.kopilot.feature.vision

import kotlin.math.roundToInt

/**
 * 4-state thermal mode used by the Vision Task Scheduler.
 * Mirrors CameraPerformanceController's 4-state ThermalState without creating circular module dependencies.
 */
enum class VisionThermalMode(val displayName: String, val targetIntervalMs: Long) {
    NORMAL("Normal", 140L),
    WARM("Warm", 220L),
    HOT("Hot", 340L),
    CRITICAL("Critical", 650L);

    companion object {
        fun fromNameOrDefault(name: String?): VisionThermalMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * Per-frame execution schedule decided by [VisionTaskScheduler] based on priority tier,
 * measured frame latency, frame age, and thermal state.
 */
data class FrameExecutionSchedule(
    val frameIndex: Int,
    val thermalMode: VisionThermalMode,
    // Tier 1: Safety-critical Road Signs & Speed Limits (Highest Priority)
    val runRoadSignYolo: Boolean,
    val runYoloHorizonZoomPass: Boolean,
    val runSignRoiExtractionAndOcr: Boolean,
    val runSignSymbolCv: Boolean,
    // Tier 2: Traffic Lights & Brake Lights
    val runTrafficAndBrakeLightAnalysis: Boolean,
    // Tier 3: Auxiliary Road Objects & Lane Geometry
    val runObjectDetection: Boolean,
    val runLaneDetection: Boolean,
    // Tier 4: Heavy Full-Frame & Dedicated Number-Plate OCR
    val runFullFrameOcr: Boolean,
    val runDedicatedPlateOcr: Boolean,
    // Stale detection expiration window
    val maxDetectionValidityMs: Long,
    val activeOptimizations: List<String>,
    val thermalWarningBanner: String?,
)

/**
 * Rolling latency and processing-frequency telemetry for a single AI/CV vision module.
 */
data class VisionModuleTelemetry(
    val moduleId: String,
    val displayName: String,
    val priorityTier: Int,
    val active: Boolean,
    val invocations: Int,
    val avgLatencyMs: Float,
    val p50LatencyMs: Float,
    val p95LatencyMs: Float,
    val p99LatencyMs: Float,
    val frequencyHz: Float,
    val scheduleDescription: String,
)

/**
 * Aggregated performance and latency percentiles across the entire RoadVisionPipeline.
 */
data class VisionPerformanceMetrics(
    val thermalMode: VisionThermalMode = VisionThermalMode.NORMAL,
    val totalFramesAnalyzed: Int = 0,
    val avgTotalLatencyMs: Float = 0f,
    val p50TotalLatencyMs: Float = 0f,
    val p95TotalLatencyMs: Float = 0f,
    val p99TotalLatencyMs: Float = 0f,
    val lastFrameLatencyMs: Long = 0L,
    val yoloAvgLatencyMs: Float = 0f,
    val signRoiOcrAvgLatencyMs: Float = 0f,
    val fullOcrAvgLatencyMs: Float = 0f,
    val plateOcrAvgLatencyMs: Float = 0f,
    val objectDetectAvgLatencyMs: Float = 0f,
    val trafficLightCvAvgLatencyMs: Float = 0f,
    val laneCvAvgLatencyMs: Float = 0f,
    val moduleTelemetry: List<VisionModuleTelemetry> = emptyList(),
    val activeOptimizations: List<String> = listOf(
        "Reusable 1:1 letterbox tensor buffers and composite ROI bitmaps active",
        "Tier 1 road-sign and speed-limit recognition running at maximum rate",
    ),
    val thermalWarningBanner: String? = null,
)

/**
 * Configurable Priority-Based AI Task Scheduler & Rolling Percentile Profiler.
 *
 * Priority hierarchy:
 * - Tier 1 (Critical Safety): Road-sign and speed-limit recognition (YOLO11n + Sign ROI OCR + Symbol CV)
 * - Tier 2 (High Safety): Traffic-light state and lead-vehicle brake-light detection
 * - Tier 3 (Guidance): Road object tracking and lane-boundary estimation
 * - Tier 4 (Advisory): Full-frame OCR and dedicated vehicle registration plate crop OCR
 *
 * Uses measured frame processing latency, frame age, and the 4-state thermal controller
 * to prevent concurrent expensive tasks from oversubscribing the CPU while never silently
 * disabling road-sign or speed-limit recognition.
 */
class VisionTaskScheduler(
    private val windowSize: Int = 60,
    private val heavyTaskLatencyBudgetMs: Long = 175L,
    private val maxFrameAgeForHeavyTasksMs: Long = 130L,
) {
    private val totalLatencyWindow = ArrayDeque<Long>(windowSize)
    private val yoloLatencyWindow = ArrayDeque<Long>(windowSize)
    private val roiOcrLatencyWindow = ArrayDeque<Long>(windowSize)
    private val fullOcrLatencyWindow = ArrayDeque<Long>(windowSize)
    private val plateOcrLatencyWindow = ArrayDeque<Long>(windowSize)
    private val objectLatencyWindow = ArrayDeque<Long>(windowSize)
    private val trafficLightLatencyWindow = ArrayDeque<Long>(windowSize)
    private val laneLatencyWindow = ArrayDeque<Long>(windowSize)

    private val yoloTimestamps = ArrayDeque<Long>(windowSize)
    private val roiOcrTimestamps = ArrayDeque<Long>(windowSize)
    private val fullOcrTimestamps = ArrayDeque<Long>(windowSize)
    private val plateOcrTimestamps = ArrayDeque<Long>(windowSize)
    private val objectTimestamps = ArrayDeque<Long>(windowSize)
    private val trafficLightTimestamps = ArrayDeque<Long>(windowSize)
    private val laneTimestamps = ArrayDeque<Long>(windowSize)

    private var yoloCount = 0
    private var roiOcrCount = 0
    private var fullOcrCount = 0
    private var plateOcrCount = 0
    private var objectCount = 0
    private var trafficLightCount = 0
    private var laneCount = 0
    private var totalFrames = 0

    @Synchronized
    fun decideSchedule(
        frameIndex: Int,
        frameAgeMs: Long,
        lastFrameLatencyMs: Long,
        thermalMode: VisionThermalMode,
        hasSignRois: Boolean,
    ): FrameExecutionSchedule {
        val deviceOverloaded = lastFrameLatencyMs > heavyTaskLatencyBudgetMs || frameAgeMs > maxFrameAgeForHeavyTasksMs

        return when (thermalMode) {
            VisionThermalMode.NORMAL -> {
                val runFullOcr = (!deviceOverloaded && (frameIndex % 2 == 1)) || (!hasSignRois && frameIndex % 3 == 0)
                val runPlateOcr = !deviceOverloaded && (frameIndex % 2 == 0)
                FrameExecutionSchedule(
                    frameIndex = frameIndex,
                    thermalMode = thermalMode,
                    runRoadSignYolo = true,
                    runYoloHorizonZoomPass = true,
                    runSignRoiExtractionAndOcr = true,
                    runSignSymbolCv = true,
                    runTrafficAndBrakeLightAnalysis = true,
                    runObjectDetection = true,
                    runLaneDetection = true,
                    runFullFrameOcr = runFullOcr,
                    runDedicatedPlateOcr = runPlateOcr,
                    maxDetectionValidityMs = 20_000L,
                    activeOptimizations = buildList {
                        add("Normal thermal mode: Full dual-pass YOLO11n + Sign ROI OCR active every frame")
                        add("Zero-allocation preallocated tensor and ROI composite buffers active")
                        if (deviceOverloaded) {
                            add("Frame latency budget guard (${lastFrameLatencyMs}ms): deferred non-critical plate OCR for 1 frame")
                        } else {
                            add("Staggered Tier 4 OCR: Full-frame OCR on odd frames, Plate crop OCR on even frames")
                        }
                    },
                    thermalWarningBanner = null,
                )
            }

            VisionThermalMode.WARM -> {
                val runObjectAndLane = (frameIndex % 2 == 0)
                val runFullOcr = !deviceOverloaded && (frameIndex % 4 == 1)
                val runPlateOcr = !deviceOverloaded && (frameIndex % 4 == 3)
                FrameExecutionSchedule(
                    frameIndex = frameIndex,
                    thermalMode = thermalMode,
                    runRoadSignYolo = true,
                    runYoloHorizonZoomPass = true,
                    runSignRoiExtractionAndOcr = true,
                    runSignSymbolCv = true,
                    runTrafficAndBrakeLightAnalysis = true,
                    runObjectDetection = runObjectAndLane,
                    runLaneDetection = runObjectAndLane,
                    runFullFrameOcr = runFullOcr,
                    runDedicatedPlateOcr = runPlateOcr,
                    maxDetectionValidityMs = 16_000L,
                    activeOptimizations = listOf(
                        "Warm thermal state: Tier 1 Road Signs & Speed Limits kept at 100% priority",
                        "Tier 3 Object & Lane analysis paced to 1/2 frame rate",
                        "Tier 4 Number-plate & full-frame OCR paced to 1/4 frame rate to reduce CPU heat",
                    ),
                    thermalWarningBanner = null,
                )
            }

            VisionThermalMode.HOT -> {
                val runObjectAndLane = (frameIndex % 3 == 0)
                val runFullOcr = !deviceOverloaded && (frameIndex % 6 == 1)
                val runPlateOcr = !deviceOverloaded && (frameIndex % 6 == 4)
                FrameExecutionSchedule(
                    frameIndex = frameIndex,
                    thermalMode = thermalMode,
                    runRoadSignYolo = true,
                    runYoloHorizonZoomPass = false, // Single-pass letterbox halves neural compute while keeping full view
                    runSignRoiExtractionAndOcr = true,
                    runSignSymbolCv = true,
                    runTrafficAndBrakeLightAnalysis = true,
                    runObjectDetection = runObjectAndLane,
                    runLaneDetection = runObjectAndLane,
                    runFullFrameOcr = runFullOcr,
                    runDedicatedPlateOcr = runPlateOcr,
                    maxDetectionValidityMs = 12_000L,
                    activeOptimizations = listOf(
                        "Hot thermal state: Single-pass full-frame YOLO11n + Sign ROI OCR active (50% neural load reduction)",
                        "Traffic-light detection remains active every analyzed frame",
                        "Auxiliary object tracking paced to 1/3 rate; plate OCR paced to 1/6 rate",
                    ),
                    thermalWarningBanner = "Device is hot: Non-critical OCR and secondary horizon pass throttled to stabilize temperature.",
                )
            }

            VisionThermalMode.CRITICAL -> {
                // Respect Android thermal protection while keeping lightweight road-sign checks alive
                val runEssentialSignPass = (frameIndex % 2 == 0)
                FrameExecutionSchedule(
                    frameIndex = frameIndex,
                    thermalMode = thermalMode,
                    runRoadSignYolo = runEssentialSignPass,
                    runYoloHorizonZoomPass = false,
                    runSignRoiExtractionAndOcr = true,
                    runSignSymbolCv = true,
                    runTrafficAndBrakeLightAnalysis = true,
                    runObjectDetection = false,
                    runLaneDetection = runEssentialSignPass,
                    runFullFrameOcr = false,
                    runDedicatedPlateOcr = false,
                    maxDetectionValidityMs = 8_000L,
                    activeOptimizations = listOf(
                        "Critical thermal protection active: Heavy full-frame OCR, plate OCR, and ML Kit object detection paused",
                        "Essential road-sign and speed-limit recognition retained in low-power single-pass mode",
                        "Stale detections expire after 8 seconds so outdated signs are never shown as current",
                    ),
                    thermalWarningBanner = "CRITICAL THERMAL PROTECTION: Device overheating. Secondary AI tasks suspended; essential road-sign detection running in low-power mode.",
                )
            }
        }
    }

    @Synchronized
    fun recordFrameTimings(
        nowMillis: Long,
        thermalMode: VisionThermalMode,
        totalFrameMs: Long,
        yoloMs: Long?,
        roiOcrMs: Long?,
        fullOcrMs: Long?,
        plateOcrMs: Long?,
        objectDetectMs: Long?,
        trafficLightMs: Long?,
        laneMs: Long?,
        schedule: FrameExecutionSchedule,
    ): VisionPerformanceMetrics {
        totalFrames += 1
        pushSample(totalLatencyWindow, totalFrameMs)

        if (yoloMs != null) {
            yoloCount += 1
            pushSample(yoloLatencyWindow, yoloMs)
            pushTimestamp(yoloTimestamps, nowMillis)
        }
        if (roiOcrMs != null) {
            roiOcrCount += 1
            pushSample(roiOcrLatencyWindow, roiOcrMs)
            pushTimestamp(roiOcrTimestamps, nowMillis)
        }
        if (fullOcrMs != null) {
            fullOcrCount += 1
            pushSample(fullOcrLatencyWindow, fullOcrMs)
            pushTimestamp(fullOcrTimestamps, nowMillis)
        }
        if (plateOcrMs != null) {
            plateOcrCount += 1
            pushSample(plateOcrLatencyWindow, plateOcrMs)
            pushTimestamp(plateOcrTimestamps, nowMillis)
        }
        if (objectDetectMs != null) {
            objectCount += 1
            pushSample(objectLatencyWindow, objectDetectMs)
            pushTimestamp(objectTimestamps, nowMillis)
        }
        if (trafficLightMs != null) {
            trafficLightCount += 1
            pushSample(trafficLightLatencyWindow, trafficLightMs)
            pushTimestamp(trafficLightTimestamps, nowMillis)
        }
        if (laneMs != null) {
            laneCount += 1
            pushSample(laneLatencyWindow, laneMs)
            pushTimestamp(laneTimestamps, nowMillis)
        }

        val modules = listOf(
            buildModuleTelemetry(
                id = "yolo11n_signs",
                name = "LiteRT YOLO11n Road Signs",
                tier = 1,
                active = schedule.runRoadSignYolo,
                count = yoloCount,
                samples = yoloLatencyWindow,
                timestamps = yoloTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = when (thermalMode) {
                    VisionThermalMode.NORMAL, VisionThermalMode.WARM -> "Every frame (Full + Horizon 2-pass)"
                    VisionThermalMode.HOT -> "Every frame (Single-pass letterbox)"
                    VisionThermalMode.CRITICAL -> "Every 2nd frame (Low-power single-pass)"
                },
            ),
            buildModuleTelemetry(
                id = "sign_roi_ocr",
                name = "Sign & Speed-Limit ROI OCR + CV",
                tier = 1,
                active = schedule.runSignRoiExtractionAndOcr,
                count = roiOcrCount,
                samples = roiOcrLatencyWindow,
                timestamps = roiOcrTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = "Every frame (Top 3 sign ROIs)",
            ),
            buildModuleTelemetry(
                id = "traffic_light_cv",
                name = "Traffic-Light & Brake-Light CV",
                tier = 2,
                active = schedule.runTrafficAndBrakeLightAnalysis,
                count = trafficLightCount,
                samples = trafficLightLatencyWindow,
                timestamps = trafficLightTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = "Every frame (3-aspect housing check)",
            ),
            buildModuleTelemetry(
                id = "road_objects",
                name = "Road Object Detection & Tracker",
                tier = 3,
                active = schedule.runObjectDetection,
                count = objectCount,
                samples = objectLatencyWindow,
                timestamps = objectTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = when (thermalMode) {
                    VisionThermalMode.NORMAL -> "Every frame"
                    VisionThermalMode.WARM -> "1/2 frame rate"
                    VisionThermalMode.HOT -> "1/3 frame rate"
                    VisionThermalMode.CRITICAL -> "Paused (Critical thermal protection)"
                },
            ),
            buildModuleTelemetry(
                id = "lane_corridor_cv",
                name = "Road Lane Corridor Estimator",
                tier = 3,
                active = schedule.runLaneDetection,
                count = laneCount,
                samples = laneLatencyWindow,
                timestamps = laneTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = when (thermalMode) {
                    VisionThermalMode.NORMAL -> "Every frame"
                    VisionThermalMode.WARM, VisionThermalMode.CRITICAL -> "1/2 frame rate"
                    VisionThermalMode.HOT -> "1/3 frame rate"
                },
            ),
            buildModuleTelemetry(
                id = "full_frame_ocr",
                name = "Full-Frame Sign & Text OCR",
                tier = 4,
                active = schedule.runFullFrameOcr,
                count = fullOcrCount,
                samples = fullOcrLatencyWindow,
                timestamps = fullOcrTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = when (thermalMode) {
                    VisionThermalMode.NORMAL -> "1/2 frame rate (Latency-guarded)"
                    VisionThermalMode.WARM -> "1/4 frame rate"
                    VisionThermalMode.HOT -> "1/6 frame rate"
                    VisionThermalMode.CRITICAL -> "Paused (Critical thermal protection)"
                },
            ),
            buildModuleTelemetry(
                id = "plate_crop_ocr",
                name = "Number-Plate Locator & Crop OCR",
                tier = 4,
                active = schedule.runDedicatedPlateOcr,
                count = plateOcrCount,
                samples = plateOcrLatencyWindow,
                timestamps = plateOcrTimestamps,
                nowMillis = nowMillis,
                scheduleDesc = when (thermalMode) {
                    VisionThermalMode.NORMAL -> "1/2 frame rate (Latency-guarded)"
                    VisionThermalMode.WARM -> "1/4 frame rate"
                    VisionThermalMode.HOT -> "1/6 frame rate"
                    VisionThermalMode.CRITICAL -> "Paused (Critical thermal protection)"
                },
            ),
        )

        return VisionPerformanceMetrics(
            thermalMode = thermalMode,
            totalFramesAnalyzed = totalFrames,
            avgTotalLatencyMs = averageOf(totalLatencyWindow),
            p50TotalLatencyMs = percentileOf(totalLatencyWindow, 50),
            p95TotalLatencyMs = percentileOf(totalLatencyWindow, 95),
            p99TotalLatencyMs = percentileOf(totalLatencyWindow, 99),
            lastFrameLatencyMs = totalFrameMs,
            yoloAvgLatencyMs = averageOf(yoloLatencyWindow),
            signRoiOcrAvgLatencyMs = averageOf(roiOcrLatencyWindow),
            fullOcrAvgLatencyMs = averageOf(fullOcrLatencyWindow),
            plateOcrAvgLatencyMs = averageOf(plateOcrLatencyWindow),
            objectDetectAvgLatencyMs = averageOf(objectLatencyWindow),
            trafficLightCvAvgLatencyMs = averageOf(trafficLightLatencyWindow),
            laneCvAvgLatencyMs = averageOf(laneLatencyWindow),
            moduleTelemetry = modules,
            activeOptimizations = schedule.activeOptimizations,
            thermalWarningBanner = schedule.thermalWarningBanner,
        )
    }

    private fun buildModuleTelemetry(
        id: String,
        name: String,
        tier: Int,
        active: Boolean,
        count: Int,
        samples: ArrayDeque<Long>,
        timestamps: ArrayDeque<Long>,
        nowMillis: Long,
        scheduleDesc: String,
    ): VisionModuleTelemetry = VisionModuleTelemetry(
        moduleId = id,
        displayName = name,
        priorityTier = tier,
        active = active,
        invocations = count,
        avgLatencyMs = averageOf(samples),
        p50LatencyMs = percentileOf(samples, 50),
        p95LatencyMs = percentileOf(samples, 95),
        p99LatencyMs = percentileOf(samples, 99),
        frequencyHz = computeFrequencyHz(timestamps, nowMillis),
        scheduleDescription = scheduleDesc,
    )

    private fun pushSample(deque: ArrayDeque<Long>, value: Long) {
        if (deque.size >= windowSize) deque.removeFirst()
        deque.addLast(value.coerceAtLeast(0L))
    }

    private fun pushTimestamp(deque: ArrayDeque<Long>, timestampMs: Long) {
        if (deque.size >= windowSize) deque.removeFirst()
        deque.addLast(timestampMs)
        while (deque.isNotEmpty() && (timestampMs - deque.first()) > 10_000L) {
            deque.removeFirst()
        }
    }

    private fun computeFrequencyHz(timestamps: ArrayDeque<Long>, nowMillis: Long): Float {
        val recent = timestamps.filter { (nowMillis - it) <= 5_000L }
        if (recent.isEmpty()) return 0f
        val spanMs = (nowMillis - recent.first()).coerceAtLeast(1000L)
        return ((recent.size * 1000f) / spanMs.toFloat() * 10f).roundToInt() / 10f
    }

    companion object {
        fun averageOf(samples: Collection<Long>): Float {
            if (samples.isEmpty()) return 0f
            return ((samples.sum().toFloat() / samples.size.toFloat()) * 10f).roundToInt() / 10f
        }

        fun percentileOf(samples: Collection<Long>, percentile: Int): Float {
            if (samples.isEmpty()) return 0f
            val sorted = samples.sorted()
            val rank = ((percentile.coerceIn(1, 99) / 100.0) * (sorted.size - 1)).roundToInt()
                .coerceIn(0, sorted.lastIndex)
            return sorted[rank].toFloat()
        }
    }
}
