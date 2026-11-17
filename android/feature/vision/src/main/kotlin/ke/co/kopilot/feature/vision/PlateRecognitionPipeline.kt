package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.mlkit.vision.text.Text
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

data class PlateQualityAssessment(
    val averageLuminance: Float,
    val contrastStdDev: Float,
    val sharpnessScore: Float,
    val isAcceptable: Boolean,
    val reason: String,
)

data class PlateObservation(
    val rawNormalizedPlate: String,
    val maskedPlate: String,
    val formatCategory: String,
    val ocrConfidence: Float,
    val qualityScore: Float,
    val consecutiveFrames: Int,
    val timestampMillis: Long,
)

/**
 * Configurable, documented Kenyan registration plate format validator.
 * Never mutates uncertain characters into guesses.
 */
object KenyanPlateValidator {

    data class FormatRule(val name: String, val regex: Regex)

    val defaultRules = listOf(
        FormatRule("Civilian Standard", Regex("^K[A-Z]{2}\\s\\d{3}[A-Z]$")),
        FormatRule("Civilian Classic", Regex("^K[A-Z]{2}\\s\\d{3}$")),
        FormatRule("Government (GK)", Regex("^GK\\s[A-Z]?\\d{3}[A-Z]?$")),
        FormatRule("Diplomatic / UN", Regex("^(CD|UN)\\s\\d{2,4}(\\s[A-Z]{1,2})?$")),
    )

    fun normalizeWithoutGuessing(rawText: String): String? {
        val cleaned = rawText.uppercase()
            .replace(Regex("[^A-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length < 5) return null

        // Insert single canonical space between prefix and digits if omitted (e.g. KDA123A -> KDA 123A)
        val compact = cleaned.replace(" ", "")
        val formatted = when {
            compact.matches(Regex("^K[A-Z]{2}\\d{3}[A-Z]$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            compact.matches(Regex("^K[A-Z]{2}\\d{3}$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            compact.matches(Regex("^GK[A-Z]?\\d{3}[A-Z]?$")) -> "GK ${compact.substring(2)}"
            else -> cleaned
        }
        return formatted
    }

    fun validateFormat(
        rawText: String,
        rules: List<FormatRule> = defaultRules,
    ): Pair<String, String>? {
        val normalized = normalizeWithoutGuessing(rawText) ?: return null
        for (rule in rules) {
            if (rule.regex.matches(normalized)) {
                return normalized to rule.name
            }
        }
        return null
    }

    /**
     * Masks middle digits/letters for privacy-preserving UI display (e.g. KDA 123A -> KDA ***A).
     */
    fun maskForDisplay(normalizedPlate: String): String {
        val parts = normalizedPlate.split(" ")
        if (parts.size < 2) return "***"
        val prefix = parts[0]
        val suffix = parts.drop(1).joinToString(" ")
        val tail = if (suffix.length >= 2 && suffix.last().isLetter()) suffix.last().toString() else ""
        return "$prefix ***$tail"
    }
}

/**
 * Pipeline C — Registration Plate Recognition:
 * Crop quality check, character confidence evaluation, multi-frame consistency,
 * format validation, privacy masking, and optional parked-only encrypted evidence capture.
 */
@Singleton
class PlateRecognitionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var candidatePlate: String? = null
    private var consecutiveMatches = 0
    private var lastSeenMs = 0L

    fun assessCropQuality(bitmap: Bitmap, box: Rect): PlateQualityAssessment {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 40 || h < 12) {
            return PlateQualityAssessment(0f, 0f, 0f, false, "Plate crop too small (${w}x${h})")
        }

        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 6).coerceAtLeast(1)
        val lums = mutableListOf<Float>()
        var edgeSum = 0f
        var edgeCount = 0

        var y = top
        while (y < bottom) {
            var prevLum: Float? = null
            var x = left
            while (x < right) {
                val px = bitmap.getPixel(x, y)
                val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
                lums.add(lum)
                if (prevLum != null) {
                    edgeSum += abs(lum - prevLum)
                    edgeCount++
                }
                prevLum = lum
                x += stepX
            }
            y += stepY
        }

        if (lums.isEmpty()) return PlateQualityAssessment(0f, 0f, 0f, false, "Empty sample")
        val mean = lums.sum() / lums.size
        var varSum = 0f
        for (l in lums) {
            val d = l - mean
            varSum += d * d
        }
        val stdDev = sqrt(varSum / lums.size)
        val sharpness = if (edgeCount > 0) edgeSum / edgeCount else 0f

        val acceptable = mean in 35f..235f && stdDev >= 16f && sharpness >= 8f
        val reason = when {
            mean < 35f -> "Too dark"
            mean > 235f -> "Overexposed glare"
            stdDev < 16f -> "Low contrast"
            sharpness < 8f -> "Motion blur"
            else -> "Clear"
        }
        return PlateQualityAssessment(mean, stdDev, sharpness, acceptable, reason)
    }

    @Synchronized
    fun evaluateOcrBlock(
        bitmap: Bitmap,
        block: Text.TextBlock,
        timestampMillis: Long,
    ): PlateObservation? {
        val box = block.boundingBox ?: return null
        val (normalized, formatName) = KenyanPlateValidator.validateFormat(block.text) ?: return null

        val quality = assessCropQuality(bitmap, box)
        if (!quality.isAcceptable) return null

        // Evaluate ML Kit symbol/element confidence if reported; reject uncertain characters
        val elementConfidences = block.lines.flatMap { it.elements }.mapNotNull { it.confidence }
        val avgConf = if (elementConfidences.isNotEmpty()) {
            elementConfidences.average().toFloat()
        } else {
            // Fallback quality-derived confidence when ML Kit version omits per-element confidence
            (0.65f + (quality.sharpnessScore / 60f)).coerceIn(0.65f, 0.90f)
        }
        if (avgConf < MIN_OCR_CONFIDENCE) return null

        // Multi-frame consistency check within 4s window
        if (normalized == candidatePlate && (timestampMillis - lastSeenMs) <= CONSISTENCY_WINDOW_MS) {
            consecutiveMatches += 1
        } else {
            candidatePlate = normalized
            consecutiveMatches = 1
        }
        lastSeenMs = timestampMillis

        if (consecutiveMatches < REQUIRED_CONSISTENT_FRAMES) return null

        return PlateObservation(
            rawNormalizedPlate = normalized,
            maskedPlate = KenyanPlateValidator.maskForDisplay(normalized),
            formatCategory = formatName,
            ocrConfidence = avgConf,
            qualityScore = (quality.sharpnessScore / 40f).coerceIn(0f, 1f),
            consecutiveFrames = consecutiveMatches,
            timestampMillis = timestampMillis,
        )
    }

    fun saveEncryptedEvidence(observation: PlateObservation): File? {
        return runCatching {
            val dir = File(context.filesDir, "plate_evidence").apply { if (!exists()) mkdirs() }
            val file = File(dir, "plate_${observation.timestampMillis}.enc")
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            val payload = "timestamp=${observation.timestampMillis}\n" +
                "plate=${observation.rawNormalizedPlate}\n" +
                "format=${observation.formatCategory}\n" +
                "confidence=${observation.ocrConfidence}\n"
            encFile.openFileOutput().use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            file
        }.getOrNull()
    }

    fun listEvidenceFiles(): List<File> {
        val dir = File(context.filesDir, "plate_evidence")
        return dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun deleteAllEvidence() {
        val dir = File(context.filesDir, "plate_evidence")
        dir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun reset() {
        candidatePlate = null
        consecutiveMatches = 0
        lastSeenMs = 0L
    }

    companion object {
        const val MIN_OCR_CONFIDENCE = 0.60f
        const val REQUIRED_CONSISTENT_FRAMES = 2
        const val CONSISTENCY_WINDOW_MS = 4_000L
    }
}
