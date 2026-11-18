package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.min

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
 * Loads `assets/models/kopilot_yolo11n_signs.tflite` and `assets/models/kopilot_yolo11n_labels.txt`,
 * attempts NNAPI hardware acceleration with automatic XNNPACK CPU fallback, and applies NMS.
 */
@Singleton
class LiteRtYoloSignDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = DEFAULT_LABELS
    private var inputWidth = 320
    private var inputHeight = 320
    private var inputDataType = DataType.FLOAT32
    private var outputShape = intArrayOf(1, 18, 2100)
    private var initialized = false

    @Volatile
    var activeAccelerator: String = "Unavailable"
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

            val interp = try {
                val nnapiOpts = Interpreter.Options().apply {
                    setUseNNAPI(true)
                    setNumThreads(2)
                }
                Interpreter(mapped, nnapiOpts).also {
                    activeAccelerator = "NNAPI (Hardware)"
                }
            } catch (_: Throwable) {
                val cpuOpts = Interpreter.Options().apply {
                    setUseXNNPACK(true)
                    setNumThreads(2)
                }
                Interpreter(mapped, cpuOpts).also {
                    activeAccelerator = "XNNPACK CPU (2 threads)"
                }
            }

            val inShape = interp.getInputTensor(0).shape() // [1, H, W, 3]
            if (inShape.size >= 4) {
                inputHeight = inShape[1].coerceAtLeast(128)
                inputWidth = inShape[2].coerceAtLeast(128)
            }
            inputDataType = interp.getInputTensor(0).dataType()
            outputShape = interp.getOutputTensor(0).shape()
            modelVersion = "yolo11n-gtsrb-litert-320"
            interpreter = interp
        }
    }

    fun detect(bitmap: Bitmap): List<YoloSignDetection> {
        ensureInitialized()
        val interp = interpreter ?: return emptyList()

        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }

        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val inputBuffer = if (inputDataType == DataType.UINT8) {
            ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    put(Color.red(px).toByte())
                    put(Color.green(px).toByte())
                    put(Color.blue(px).toByte())
                }
                rewind()
            }
        } else {
            ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    putFloat(Color.red(px) / 255f)
                    putFloat(Color.green(px) / 255f)
                    putFloat(Color.blue(px) / 255f)
                }
                rewind()
            }
        }

        return runCatching {
            if (outputShape.size < 3) return emptyList()
            val d1 = outputShape[1]
            val d2 = outputShape[2]
            val outArr = Array(1) { Array(d1) { FloatArray(d2) } }
            interp.run(inputBuffer, outArr)

            // Ultralytics YOLO11 TFLite output is typically [1, 4 + NC, numBoxes]
            val numClasses = labels.size
            val isChannelsFirst = (d1 == 4 + numClasses) || (d1 < d2)
            val numBoxes = if (isChannelsFirst) d2 else d1
            val channels = if (isChannelsFirst) d1 else d2
            val classCount = min(numClasses, (channels - 4).coerceAtLeast(0))
            if (classCount <= 0) return emptyList()

            val candidates = ArrayList<YoloSignDetection>()
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

                // Support both normalized [0..1] and pixel [0..inputSize] coordinates
                if (cx > 2f || cy > 2f || bw > 2f || bh > 2f) {
                    cx /= inputWidth.toFloat()
                    cy /= inputHeight.toFloat()
                    bw /= inputWidth.toFloat()
                    bh /= inputHeight.toFloat()
                }

                val left = (cx - bw / 2f).coerceIn(0f, 1f)
                val top = (cy - bh / 2f).coerceIn(0f, 1f)
                val right = (cx + bw / 2f).coerceIn(0f, 1f)
                val bottom = (cy + bh / 2f).coerceIn(0f, 1f)
                if (right - left < 0.02f || bottom - top < 0.02f) continue

                val labelName = labels.getOrNull(bestCls) ?: continue
                val category = mapLabelToCategory(labelName) ?: continue
                candidates.add(
                    YoloSignDetection(
                        category = category,
                        label = labelName,
                        confidence = bestScore.coerceIn(0f, 0.99f),
                        normalizedLeft = left,
                        normalizedTop = top,
                        normalizedRight = right,
                        normalizedBottom = bottom,
                    ),
                )
            }

            applyNms(candidates, NMS_IOU_THRESHOLD)
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
