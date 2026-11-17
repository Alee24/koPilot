package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Pipeline A (LiteRT YOLO / SSD Object Detector):
 * Runs `assets/models/road_object_detector.tflite` when bundled for Kenyan road classes
 * (car, bus, truck, motorcycle, bicycle, pedestrian, animal). Gracefully reports
 * `isAvailable = false` when absent so ML Kit ObjectDetector + MultiObjectTracker handles tracking.
 */
@Singleton
class LiteRtObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var inputWidth = 320
    private var inputHeight = 320
    private var inputDataType = DataType.FLOAT32
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
            val files = context.assets.list("models").orEmpty()
            if (!files.contains(MODEL_FILE)) return
            val afd = context.assets.openFd("models/$MODEL_FILE")
            val mapped: MappedByteBuffer = FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
            val interp = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) })
            val shape = interp.getInputTensor(0).shape()
            if (shape.size >= 4) {
                inputHeight = shape[1].coerceAtLeast(128)
                inputWidth = shape[2].coerceAtLeast(128)
            }
            inputDataType = interp.getInputTensor(0).dataType()
            interpreter = interp
        }
    }

    fun detect(bitmap: Bitmap): List<RawObjectDetection> {
        ensureInitialized()
        val interp = interpreter ?: return emptyList()
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val inputBuf = if (inputDataType == DataType.UINT8) {
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

        // Standard SSD-MobileNet / TFLite DetectionPostProcess output signature [1, N, 4], [1, N], [1, N], [1]
        return runCatching {
            val maxDet = 10
            val boxes = Array(1) { Array(maxDet) { FloatArray(4) } }
            val classes = Array(1) { FloatArray(maxDet) }
            val scores = Array(1) { FloatArray(maxDet) }
            val count = FloatArray(1)
            val outputs = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

            val n = count[0].toInt().coerceIn(0, maxDet)
            (0 until n).mapNotNull { i ->
                val conf = scores[0][i]
                if (conf < 0.45f) return@mapNotNull null
                val clsIdx = classes[0][i].toInt()
                val label = SUPPORTED_CLASSES.getOrElse(clsIdx) { "Vehicle" }
                val b = boxes[0][i]
                RawObjectDetection(
                    label = label,
                    confidence = conf,
                    normalizedTop = b[0].coerceIn(0f, 1f),
                    normalizedLeft = b[1].coerceIn(0f, 1f),
                    normalizedBottom = b[2].coerceIn(0f, 1f),
                    normalizedRight = b[3].coerceIn(0f, 1f),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MODEL_FILE = "road_object_detector.tflite"
        private val SUPPORTED_CLASSES = listOf(
            "Car", "Bus", "Truck", "Motorcycle", "Bicycle", "Pedestrian", "Animal",
        )
    }
}
