package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class LiteRtAcceleratorBackend(
    val displayName: String,
    val useNnapi: Boolean,
    val requestedThreads: Int,
) {
    AUTO_XNNPACK("Auto XNNPACK CPU", useNnapi = false, requestedThreads = 0),
    XNNPACK_2_THREADS("XNNPACK CPU (2 Threads)", useNnapi = false, requestedThreads = 2),
    XNNPACK_4_THREADS("XNNPACK CPU (4 Threads)", useNnapi = false, requestedThreads = 4),
    NNAPI("Android NNAPI Delegate", useNnapi = true, requestedThreads = 2),
}

data class AcceleratorBenchmarkResult(
    val backend: LiteRtAcceleratorBackend,
    val available: Boolean,
    val avgLatencyMs: Float,
    val p50LatencyMs: Float,
    val p95LatencyMs: Float,
    val p99LatencyMs: Float,
    val maxOutputDeviation: Float,
    val numericallyConsistent: Boolean,
    val selectedAsBest: Boolean,
    val statusNote: String,
)

data class YoloSignDetection(
    val category: TrafficSignCategory,
    val label: String,
    val confidence: Float,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
)

/**
 * Offline LiteRT YOLO11n Road-Sign Detector.
 * - Preserves 1:1 aspect ratio via letterbox padding across the full camera view.
 * - Executes a second high-resolution forward-horizon square crop pass so small and distant signs
 *   while driving retain ~2x pixel density without losing full-frame coverage.
 * - Reuses preallocated native ByteBuffers and pixel arrays to eliminate GC churn.
 * - Verifies detections against road-sign color & contrast geometry to reject indoor/clutter false positives.
 */
@Singleton
class LiteRtYoloSignDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var mappedModelBuffer: MappedByteBuffer? = null
    private var labels: List<String> = DEFAULT_LABELS
    private var inputWidth = 320
    private var inputHeight = 320
    private var inputDataType = DataType.FLOAT32
    private var outputShape = intArrayOf(1, 18, 2100)
    private var initialized = false

    // Pre-allocated reusable buffers (allocated once per model input size)
    private var letterboxBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var reusablePixels: IntArray = IntArray(0)
    private var reusableFloatBuffer: ByteBuffer? = null
    private var reusableUint8Buffer: ByteBuffer? = null
    private var reusableOutArr: Array<Array<FloatArray>>? = null

    @Volatile
    var selectedBackend: LiteRtAcceleratorBackend = LiteRtAcceleratorBackend.AUTO_XNNPACK
        private set

    @Volatile
    var activeAccelerator: String = "Unavailable"
        private set

    @Volatile
    var lastBenchmarkResults: List<AcceleratorBenchmarkResult> = emptyList()
        private set

    @Volatile
    var modelVersion: String = "yolo11n-unbundled"
        private set

    val supportedLabels: List<String>
        get() = labels

    val isAvailable: Boolean
        get() {
            ensureInitialized()
            return interpreter != null
        }

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        runCatching {
            val files = context.assets.list("models").orEmpty()
            if (!files.contains(MODEL_FILE)) return

            if (files.contains(LABELS_FILE)) {
                val loadedLabels = context.assets.open("models/$LABELS_FILE").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
                if (loadedLabels.isNotEmpty()) {
                    labels = loadedLabels
                }
            }

            val afd = context.assets.openFd("models/$MODEL_FILE")
            val mapped: MappedByteBuffer = FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
            mappedModelBuffer = mapped

            val interp = createInterpreterForBackend(mapped, selectedBackend)
            val inShape = interp.getInputTensor(0).shape() // [1, H, W, 3]
            if (inShape.size >= 4) {
                inputHeight = inShape[1].coerceAtLeast(128)
                inputWidth = inShape[2].coerceAtLeast(128)
            }
            inputDataType = interp.getInputTensor(0).dataType()
            outputShape = interp.getOutputTensor(0).shape()
            allocateReusableBuffers()
            modelVersion = "yolo11n-gtsrb-litert-320"
            interpreter = interp
        }
    }

    private fun resolveThreads(backend: LiteRtAcceleratorBackend): Int {
        return if (backend.requestedThreads > 0) {
            backend.requestedThreads
        } else {
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        }
    }

    private fun createInterpreterForBackend(
        mapped: MappedByteBuffer,
        backend: LiteRtAcceleratorBackend,
    ): Interpreter {
        val threads = resolveThreads(backend)
        return if (backend.useNnapi) {
            val nnapiOpts = Interpreter.Options().apply {
                setUseNNAPI(true)
                setNumThreads(threads)
            }
            Interpreter(mapped, nnapiOpts).also {
                activeAccelerator = "NNAPI Delegate ($threads threads)"
            }
        } else {
            try {
                val cpuOpts = Interpreter.Options().apply {
                    setUseXNNPACK(true)
                    setNumThreads(threads)
                }
                Interpreter(mapped, cpuOpts).also {
                    activeAccelerator = "XNNPACK CPU ($threads threads)"
                }
            } catch (_: Throwable) {
                val fallbackOpts = Interpreter.Options().apply {
                    setNumThreads(threads)
                }
                Interpreter(mapped, fallbackOpts).also {
                    activeAccelerator = "LiteRT CPU ($threads threads)"
                }
            }
        }
    }

    @Synchronized
    fun setPreferredBackend(backend: LiteRtAcceleratorBackend): Boolean {
        ensureInitialized()
        val mapped = mappedModelBuffer ?: return false
        return runCatching {
            val newInterp = createInterpreterForBackend(mapped, backend)
            val oldInterp = interpreter
            interpreter = newInterp
            selectedBackend = backend
            runCatching { oldInterp?.close() }
            true
        }.getOrDefault(false)
    }

    /**
     * Runs an on-device hardware acceleration benchmark across XNNPACK (2 threads, 4 threads)
     * and Android NNAPI, verifying numerical output consistency against XNNPACK before selecting
     * the fastest valid execution backend on the actual physical device.
     */
    @Synchronized
    fun runOnDeviceBenchmark(iterations: Int = 6): List<AcceleratorBenchmarkResult> {
        ensureInitialized()
        val mapped = mappedModelBuffer ?: return emptyList()
        val d1 = outputShape.getOrElse(1) { 18 }
        val d2 = outputShape.getOrElse(2) { 2100 }

        // Deterministic synthetic road-sign pattern buffer for repeatable latency & numerical comparison
        val testBuffer: ByteBuffer = if (inputDataType == DataType.UINT8) {
            ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder()).apply {
                for (i in 0 until inputWidth * inputHeight) {
                    put(((i * 31) and 0xFF).toByte())
                    put(((i * 17) and 0xFF).toByte())
                    put(((i * 13) and 0xFF).toByte())
                }
                rewind()
            }
        } else {
            ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder()).apply {
                val inv255 = 1f / 255f
                for (i in 0 until inputWidth * inputHeight) {
                    putFloat(((i * 31) and 0xFF) * inv255)
                    putFloat(((i * 17) and 0xFF) * inv255)
                    putFloat(((i * 13) and 0xFF) * inv255)
                }
                rewind()
            }
        }

        val candidates = listOf(
            LiteRtAcceleratorBackend.XNNPACK_2_THREADS,
            LiteRtAcceleratorBackend.XNNPACK_4_THREADS,
            LiteRtAcceleratorBackend.NNAPI,
        )

        var referenceOutput: Array<Array<FloatArray>>? = null
        val rawResults = mutableListOf<AcceleratorBenchmarkResult>()

        for (candidate in candidates) {
            val threads = resolveThreads(candidate)
            val result = runCatching {
                val opts = Interpreter.Options().apply {
                    if (candidate.useNnapi) {
                        setUseNNAPI(true)
                    } else {
                        setUseXNNPACK(true)
                    }
                    setNumThreads(threads)
                }
                val tempInterp = Interpreter(mapped, opts)
                try {
                    val outTensor = Array(1) { Array(d1) { FloatArray(d2) } }
                    // Warm-up pass
                    testBuffer.rewind()
                    tempInterp.run(testBuffer, outTensor)

                    val timings = ArrayList<Long>(iterations)
                    val runs = iterations.coerceIn(3, 12)
                    for (r in 0 until runs) {
                        testBuffer.rewind()
                        val t0 = System.nanoTime()
                        tempInterp.run(testBuffer, outTensor)
                        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                        timings.add(elapsedMs)
                    }

                    var maxDiff = 0f
                    if (referenceOutput == null) {
                        referenceOutput = outTensor
                    } else {
                        val ref = referenceOutput!!
                        for (c in 0 until d1) {
                            for (b in 0 until min(d2, 400)) {
                                val diff = abs(ref[0][c][b] - outTensor[0][c][b])
                                if (diff > maxDiff) maxDiff = diff
                            }
                        }
                    }

                    val numericallyValid = maxDiff <= 0.05f
                    val avg = VisionTaskScheduler.averageOf(timings)
                    val p50 = VisionTaskScheduler.percentileOf(timings, 50)
                    val p95 = VisionTaskScheduler.percentileOf(timings, 95)
                    val p99 = VisionTaskScheduler.percentileOf(timings, 99)

                    AcceleratorBenchmarkResult(
                        backend = candidate,
                        available = true,
                        avgLatencyMs = avg,
                        p50LatencyMs = p50,
                        p95LatencyMs = p95,
                        p99LatencyMs = p99,
                        maxOutputDeviation = ((maxDiff * 10000f).roundToInt() / 10000f),
                        numericallyConsistent = numericallyValid,
                        selectedAsBest = false,
                        statusNote = if (numericallyValid) {
                            "Verified (Max tensor diff ${((maxDiff * 1000f).roundToInt() / 1000f)})"
                        } else {
                            "Rejected: Output tensor deviation $maxDiff exceeds 0.05 threshold"
                        },
                    )
                } finally {
                    runCatching { tempInterp.close() }
                }
            }.getOrElse { err ->
                AcceleratorBenchmarkResult(
                    backend = candidate,
                    available = false,
                    avgLatencyMs = 0f,
                    p50LatencyMs = 0f,
                    p95LatencyMs = 0f,
                    p99LatencyMs = 0f,
                    maxOutputDeviation = 0f,
                    numericallyConsistent = false,
                    selectedAsBest = false,
                    statusNote = "Unsupported or failed: ${err.localizedMessage ?: "delegate error"}",
                )
            }
            rawResults.add(result)
        }

        val bestCandidate = rawResults
            .filter { it.available && it.numericallyConsistent && it.avgLatencyMs > 0f }
            .minByOrNull { it.avgLatencyMs }
            ?.backend ?: LiteRtAcceleratorBackend.AUTO_XNNPACK

        setPreferredBackend(bestCandidate)

        val finalized = rawResults.map { res ->
            res.copy(selectedAsBest = (res.backend == bestCandidate))
        }
        lastBenchmarkResults = finalized
        return finalized
    }

    private fun allocateReusableBuffers() {
        val bmp = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        letterboxBitmap = bmp
        letterboxCanvas = Canvas(bmp)
        reusablePixels = IntArray(inputWidth * inputHeight)
        if (inputDataType == DataType.UINT8) {
            reusableUint8Buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
        } else {
            reusableFloatBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
        }
        if (outputShape.size >= 3) {
            val d1 = outputShape[1]
            val d2 = outputShape[2]
            reusableOutArr = Array(1) { Array(d1) { FloatArray(d2) } }
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap, includeHorizonZoomPass: Boolean = true): List<YoloSignDetection> {
        ensureInitialized()
        val interp = interpreter ?: return emptyList()
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW < 32 || srcH < 32) return emptyList()

        val allCandidates = ArrayList<YoloSignDetection>()

        // Pass 1: Full-frame aspect-ratio preserving letterbox pass (keeps 100% of camera view active)
        val fullFrameRect = Rect(0, 0, srcW, srcH)
        allCandidates.addAll(runTileInference(interp, bitmap, fullFrameRect, srcW, srcH))

        // Pass 2: High-resolution 1:1 horizon corridor pass for small & distant signs while driving
        val hasHighConfAlready = allCandidates.any { it.confidence >= 0.78f }
        if (includeHorizonZoomPass && !hasHighConfAlready && max(srcW, srcH) >= 480) {
            val cropSide = (min(srcW, srcH) * 0.78f).toInt().coerceAtLeast(160)
            val cropLeft = ((srcW - cropSide) / 2).coerceAtLeast(0)
            // Center vertically around y = 0.40 (road sign horizon)
            val cropTop = ((srcH * 0.40f) - (cropSide * 0.5f)).toInt().coerceIn(0, (srcH - cropSide).coerceAtLeast(0))
            val horizonRect = Rect(
                cropLeft,
                cropTop,
                (cropLeft + cropSide).coerceAtMost(srcW),
                (cropTop + cropSide).coerceAtMost(srcH),
            )
            allCandidates.addAll(runTileInference(interp, bitmap, horizonRect, srcW, srcH))
        }

        // Verify candidate bounding boxes against sign color/contrast geometry to reject indoor/roadworks false positives
        val verified = allCandidates.filter { det ->
            SignRoiExtractor.verifyYoloDetectionBox(
                bitmap = bitmap,
                normLeft = det.normalizedLeft,
                normTop = det.normalizedTop,
                normRight = det.normalizedRight,
                normBottom = det.normalizedBottom,
                category = det.category,
            )
        }

        return applyNms(verified, NMS_IOU_THRESHOLD)
    }

    private fun runTileInference(
        interp: Interpreter,
        sourceBitmap: Bitmap,
        srcRect: Rect,
        fullWidth: Int,
        fullHeight: Int,
    ): List<YoloSignDetection> {
        val lbBmp = letterboxBitmap ?: return emptyList()
        val lbCanvas = letterboxCanvas ?: return emptyList()
        val outArr = reusableOutArr ?: return emptyList()

        val tileW = srcRect.width().coerceAtLeast(1)
        val tileH = srcRect.height().coerceAtLeast(1)

        // Uniform scale to preserve exact 1:1 aspect ratio of circular/triangular signs
        val scale = min(inputWidth.toFloat() / tileW.toFloat(), inputHeight.toFloat() / tileH.toFloat())
        val drawnW = tileW * scale
        val drawnH = tileH * scale
        val padX = (inputWidth - drawnW) * 0.5f
        val padY = (inputHeight - drawnH) * 0.5f

        // Fill letterbox background with neutral YOLO grey (114, 114, 114)
        lbCanvas.drawColor(Color.rgb(114, 114, 114))
        val dstRect = RectF(padX, padY, padX + drawnW, padY + drawnH)
        lbCanvas.drawBitmap(sourceBitmap, srcRect, dstRect, bitmapPaint)

        lbBmp.getPixels(reusablePixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val inputBuffer: ByteBuffer = if (inputDataType == DataType.UINT8) {
            val buf = reusableUint8Buffer ?: return emptyList()
            buf.clear()
            for (px in reusablePixels) {
                buf.put(Color.red(px).toByte())
                buf.put(Color.green(px).toByte())
                buf.put(Color.blue(px).toByte())
            }
            buf.rewind()
            buf
        } else {
            val buf = reusableFloatBuffer ?: return emptyList()
            buf.clear()
            val inv255 = 1f / 255f
            for (px in reusablePixels) {
                buf.putFloat(((px shr 16) and 0xFF) * inv255)
                buf.putFloat(((px shr 8) and 0xFF) * inv255)
                buf.putFloat((px and 0xFF) * inv255)
            }
            buf.rewind()
            buf
        }

        return runCatching {
            interp.run(inputBuffer, outArr)

            val d1 = outputShape[1]
            val d2 = outputShape[2]
            val numClasses = labels.size
            val isChannelsFirst = (d1 == 4 + numClasses) || (d1 < d2)
            val numBoxes = if (isChannelsFirst) d2 else d1
            val channels = if (isChannelsFirst) d1 else d2
            val classCount = min(numClasses, (channels - 4).coerceAtLeast(0))
            if (classCount <= 0) return emptyList()

            val results = ArrayList<YoloSignDetection>()
            for (b in 0 until numBoxes) {
                var bestCls = -1
                var bestScore = CONFIDENCE_THRESHOLD
                for (c in 0 until classCount) {
                    val score = if (isChannelsFirst) outArr[0][4 + c][b] else outArr[0][b][4 + c]
                    if (score > bestScore) {
                        bestScore = score
                        bestCls = c
                    }
                }
                if (bestCls < 0) continue

                var cx = if (isChannelsFirst) outArr[0][0][b] else outArr[0][b][0]
                var cy = if (isChannelsFirst) outArr[0][1][b] else outArr[0][b][1]
                var bw = if (isChannelsFirst) outArr[0][2][b] else outArr[0][b][2]
                var bh = if (isChannelsFirst) outArr[0][3][b] else outArr[0][b][3]

                // Convert normalized [0..1] model coords to [0..inputSize] pixel coords for un-letterboxing
                if (cx <= 2f && cy <= 2f && bw <= 2f && bh <= 2f) {
                    cx *= inputWidth.toFloat()
                    cy *= inputHeight.toFloat()
                    bw *= inputWidth.toFloat()
                    bh *= inputHeight.toFloat()
                }

                // Un-letterbox from [0..inputWidth, 0..inputHeight] back to source tile pixels, then full-frame normalized [0..1]
                val tileLeftPx = (cx - bw * 0.5f - padX) / scale
                val tileTopPx = (cy - bh * 0.5f - padY) / scale
                val tileRightPx = (cx + bw * 0.5f - padX) / scale
                val tileBottomPx = (cy + bh * 0.5f - padY) / scale

                val fullLeft = ((srcRect.left + tileLeftPx) / fullWidth.toFloat()).coerceIn(0f, 1f)
                val fullTop = ((srcRect.top + tileTopPx) / fullHeight.toFloat()).coerceIn(0f, 1f)
                val fullRight = ((srcRect.left + tileRightPx) / fullWidth.toFloat()).coerceIn(0f, 1f)
                val fullBottom = ((srcRect.top + tileBottomPx) / fullHeight.toFloat()).coerceIn(0f, 1f)

                val normW = fullRight - fullLeft
                val normH = fullBottom - fullTop
                if (normW < 0.015f || normH < 0.015f || normW > 0.65f || normH > 0.65f) continue

                val labelName = labels.getOrNull(bestCls) ?: continue
                val category = mapLabelToCategory(labelName) ?: continue
                // Require higher confidence for ROADWORKS to prevent yellow-object false positives
                if (category == TrafficSignCategory.ROADWORKS && bestScore < 0.58f) continue

                results.add(
                    YoloSignDetection(
                        category = category,
                        label = labelName,
                        confidence = bestScore.coerceIn(0f, 0.99f),
                        normalizedLeft = fullLeft,
                        normalizedTop = fullTop,
                        normalizedRight = fullRight,
                        normalizedBottom = fullBottom,
                    ),
                )
            }
            results
        }.getOrDefault(emptyList())
    }

    private fun applyNms(detections: List<YoloSignDetection>, iouThreshold: Float): List<YoloSignDetection> {
        if (detections.size <= 1) return detections
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = ArrayList<YoloSignDetection>()
        for (cand in sorted) {
            val suppressed = kept.any { existing ->
                existing.category == cand.category && iou(existing, cand) > iouThreshold
            }
            if (!suppressed) {
                kept.add(cand)
                if (kept.size >= MAX_DETECTIONS) break
            }
        }
        return kept
    }

    private fun iou(a: YoloSignDetection, b: YoloSignDetection): Float {
        val ix1 = max(a.normalizedLeft, b.normalizedLeft)
        val iy1 = max(a.normalizedTop, b.normalizedTop)
        val ix2 = min(a.normalizedRight, b.normalizedRight)
        val iy2 = min(a.normalizedBottom, b.normalizedBottom)
        val interW = (ix2 - ix1).coerceAtLeast(0f)
        val interH = (iy2 - iy1).coerceAtLeast(0f)
        val interArea = interW * interH
        val areaA = (a.normalizedRight - a.normalizedLeft) * (a.normalizedBottom - a.normalizedTop)
        val areaB = (b.normalizedRight - b.normalizedLeft) * (b.normalizedBottom - b.normalizedTop)
        val union = (areaA + areaB - interArea).coerceAtLeast(1e-6f)
        return interArea / union
    }

    private fun mapLabelToCategory(label: String): TrafficSignCategory? = when (label.uppercase()) {
        "SPEED_LIMIT_30" -> TrafficSignCategory.SPEED_LIMIT_30
        "SPEED_LIMIT_50" -> TrafficSignCategory.SPEED_LIMIT_50
        "SPEED_LIMIT_60" -> TrafficSignCategory.SPEED_LIMIT_60
        "SPEED_LIMIT_80" -> TrafficSignCategory.SPEED_LIMIT_80
        "SPEED_LIMIT_100" -> TrafficSignCategory.SPEED_LIMIT_100
        "SPEED_LIMIT_110" -> TrafficSignCategory.SPEED_LIMIT_110
        "STOP" -> TrafficSignCategory.STOP
        "GIVE_WAY" -> TrafficSignCategory.GIVE_WAY
        "NO_ENTRY" -> TrafficSignCategory.NO_ENTRY
        "PEDESTRIAN_CROSSING" -> TrafficSignCategory.PEDESTRIAN_CROSSING
        "SCHOOL_ZONE" -> TrafficSignCategory.SCHOOL_ZONE
        "ROADWORKS" -> TrafficSignCategory.ROADWORKS
        "DANGEROUS_BEND" -> TrafficSignCategory.DANGEROUS_BEND
        "SPEED_BUMP" -> TrafficSignCategory.SPEED_BUMP
        "NO_OVERTAKING" -> TrafficSignCategory.NO_OVERTAKING
        "ROUNDABOUT" -> TrafficSignCategory.ROUNDABOUT
        "RAILWAY_CROSSING" -> TrafficSignCategory.RAILWAY_CROSSING
        "HOSPITAL_ZONE" -> TrafficSignCategory.HOSPITAL_ZONE
        "BLACKSPOT" -> TrafficSignCategory.BLACKSPOT
        "POLICE_CHECKPOINT" -> TrafficSignCategory.POLICE_CHECKPOINT
        else -> null
    }

    companion object {
        private const val MODEL_FILE = "kopilot_yolo11n_signs.tflite"
        private const val LABELS_FILE = "kopilot_yolo11n_labels.txt"
        private const val CONFIDENCE_THRESHOLD = 0.40f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 5
        private val DEFAULT_LABELS = listOf(
            "SPEED_LIMIT_30",
            "SPEED_LIMIT_50",
            "SPEED_LIMIT_60",
            "SPEED_LIMIT_80",
            "SPEED_LIMIT_100",
            "STOP",
            "GIVE_WAY",
            "NO_ENTRY",
            "PEDESTRIAN_CROSSING",
            "ROADWORKS",
            "DANGEROUS_BEND",
            "SPEED_BUMP",
            "NO_OVERTAKING",
            "ROUNDABOUT",
        )
    }
}
