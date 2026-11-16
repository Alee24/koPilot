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

/**
 * Option 2 (Two-Stage On-Device AI):
 * Runs a lightweight quantized LiteRT/TFLite road-sign classifier on localized sign crops
 * when `assets/models/road_sign_classifier.tflite` is bundled. Gracefully no-ops if absent
 * so zero-model CV + full-frame OCR continue uninterrupted.
 */
@Singleton
class LiteRtSignClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth: Int = 64
    private var inputHeight: Int = 64
    private var inputDataType: DataType = DataType.FLOAT32
    private var initialized = false

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
            val modelFiles = context.assets.list("models").orEmpty()
            if (!modelFiles.contains(MODEL_FILE_NAME)) return

            val buffer = loadModelFile("models/$MODEL_FILE_NAME")
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            val interp = Interpreter(buffer, options)
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape() // [1, H, W, 3]
            if (shape.size >= 4) {
                inputHeight = shape[1].coerceAtLeast(32)
                inputWidth = shape[2].coerceAtLeast(32)
            }
            inputDataType = inputTensor.dataType()

            labels = if (modelFiles.contains(LABELS_FILE_NAME)) {
                context.assets.open("models/$LABELS_FILE_NAME").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            } else {
                DEFAULT_LABELS
            }
            interpreter = interp
        }
    }

    fun classifyCrop(crop: Bitmap, normalizedCenterX: Float): RawSignDetection? {
        ensureInitialized()
        val interp = interpreter ?: return null
        if (crop.width < 16 || crop.height < 16) return null

        val scaled = Bitmap.createScaledBitmap(crop, inputWidth, inputHeight, true)
        val inputBuffer = buildInputBuffer(scaled)
        val numClasses = interp.getOutputTensor(0).shape().lastOrNull()?.coerceAtLeast(1) ?: return null

        val outputType = interp.getOutputTensor(0).dataType()
        val scores = FloatArray(numClasses)

        if (outputType == DataType.UINT8) {
            val outBytes = Array(1) { ByteArray(numClasses) }
            interp.run(inputBuffer, outBytes)
            for (i in 0 until numClasses) {
                scores[i] = (outBytes[0][i].toInt() and 0xFF) / 255f
            }
        } else {
            val outFloats = Array(1) { FloatArray(numClasses) }
            interp.run(inputBuffer, outFloats)
            for (i in 0 until numClasses) {
                scores[i] = outFloats[0][i]
            }
        }

        var bestIdx = 0
        var bestScore = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }

        if (bestScore < MIN_CONFIDENCE) return null
        val labelName = labels.getOrNull(bestIdx) ?: return null
        val category = mapLabelToCategory(labelName) ?: return null
        return RawSignDetection(
            category = category,
            confidence = bestScore.coerceIn(0.55f, 0.96f),
            normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
        )
    }

    private fun buildInputBuffer(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        return if (inputDataType == DataType.UINT8) {
            val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
            for (px in pixels) {
                buffer.put(Color.red(px).toByte())
                buffer.put(Color.green(px).toByte())
                buffer.put(Color.blue(px).toByte())
            }
            buffer.rewind()
            buffer
        } else {
            val buffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
            for (px in pixels) {
                buffer.putFloat(Color.red(px) / 255f)
                buffer.putFloat(Color.green(px) / 255f)
                buffer.putFloat(Color.blue(px) / 255f)
            }
            buffer.rewind()
            buffer
        }
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetPath)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    internal fun mapLabelToCategory(label: String): TrafficSignCategory? {
        val norm = label.uppercase().replace("-", "_").replace(" ", "_").trim()
        return TrafficSignCategory.entries.firstOrNull { it.name == norm } ?: when {
            norm.contains("30") -> TrafficSignCategory.SPEED_LIMIT_30
            norm.contains("50") -> TrafficSignCategory.SPEED_LIMIT_50
            norm.contains("60") -> TrafficSignCategory.SPEED_LIMIT_60
            norm.contains("80") -> TrafficSignCategory.SPEED_LIMIT_80
            norm.contains("100") -> TrafficSignCategory.SPEED_LIMIT_100
            norm.contains("110") -> TrafficSignCategory.SPEED_LIMIT_110
            norm.contains("STOP") -> TrafficSignCategory.STOP
            norm.contains("GIVE_WAY") || norm.contains("YIELD") -> TrafficSignCategory.GIVE_WAY
            norm.contains("NO_ENTRY") -> TrafficSignCategory.NO_ENTRY
            norm.contains("PEDESTRIAN") || norm.contains("CROSSING") -> TrafficSignCategory.PEDESTRIAN_CROSSING
            norm.contains("SCHOOL") -> TrafficSignCategory.SCHOOL_ZONE
            norm.contains("ROADWORK") || norm.contains("CONSTRUCTION") -> TrafficSignCategory.ROADWORKS
            norm.contains("BEND") || norm.contains("CURVE") -> TrafficSignCategory.DANGEROUS_BEND
            norm.contains("BUMP") || norm.contains("HUMP") -> TrafficSignCategory.SPEED_BUMP
            norm.contains("OVERTAKING") -> TrafficSignCategory.NO_OVERTAKING
            norm.contains("ROUNDABOUT") -> TrafficSignCategory.ROUNDABOUT
            norm.contains("RAILWAY") -> TrafficSignCategory.RAILWAY_CROSSING
            norm.contains("HOSPITAL") -> TrafficSignCategory.HOSPITAL_ZONE
            else -> null
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "road_sign_classifier.tflite"
        private const val LABELS_FILE_NAME = "road_sign_labels.txt"
        private const val MIN_CONFIDENCE = 0.58f

        private val DEFAULT_LABELS = listOf(
            "SPEED_LIMIT_30",
            "SPEED_LIMIT_50",
            "SPEED_LIMIT_60",
            "SPEED_LIMIT_80",
            "SPEED_LIMIT_100",
            "SPEED_LIMIT_110",
            "STOP",
            "GIVE_WAY",
            "NO_ENTRY",
            "PEDESTRIAN_CROSSING",
            "SCHOOL_ZONE",
            "ROADWORKS",
            "DANGEROUS_BEND",
            "SPEED_BUMP",
            "NO_OVERTAKING",
            "ROUNDABOUT",
            "RAILWAY_CROSSING",
        )
    }
}
