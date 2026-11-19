package ke.co.kopilot.feature.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.mlkit.vision.text.Text
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class PlateSurfaceColor(val label: String) {
    WHITE_BLACK_TEXT("White Plate (Front/Standard)"),
    YELLOW_BLACK_TEXT("Yellow Plate (Rear/Commercial)"),
    OTHER_CONTRAST("High-Contrast Plate"),
}

data class PlateRegionCandidate(
    val rect: Rect,
    val crop: Bitmap,
    val surfaceColor: PlateSurfaceColor,
    val edgeScore: Float,
)

data class PlateQualityAssessment(
    val averageLuminance: Float,
    val contrastStdDev: Float,
    val sharpnessScore: Float,
    val isAcceptable: Boolean,
    val reason: String,
    val surfaceColor: PlateSurfaceColor = PlateSurfaceColor.OTHER_CONTRAST,
)

data class PlateObservation(
    val rawNormalizedPlate: String,
    val maskedPlate: String,
    val formatCategory: String,
    val ocrConfidence: Float,
    val qualityScore: Float,
    val consecutiveFrames: Int,
    val timestampMillis: Long,
    val surfaceColor: PlateSurfaceColor = PlateSurfaceColor.OTHER_CONTRAST,
)

/**
 * Configurable registration plate format validator supporting Kenyan (Civilian, GK, Diplomatic/UN),
 * East African Community (EAC), and international alphanumeric registration formats.
 * Never fabricates or guesses unreadable characters.
 */
object KenyanPlateValidator {

    data class FormatRule(val name: String, val regex: Regex)

    private val NON_PLATE_WORDS = setOf(
        "SPEED", "LIMIT", "KPH", "KMH", "MAX", "STOP", "SLOW", "EXIT", "ROAD",
        "TOYOTA", "NISSAN", "ISUZU", "SUBARU", "MAZDA", "HONDA", "VOLVO", "SCANIA",
        "MERCEDES", "MITSUBISHI", "FORD", "SUZUKI", "HYUNDAI", "KIA", "PEUGEOT",
        "POLICE", "AMBULANCE", "SCHOOL", "TRANSIT", "SAFARI", "EXPRESS", "KENYA",
    )

    val defaultRules = listOf(
        FormatRule("Civilian Standard", Regex("^K[A-Z]{2}\\s\\d{3}[A-Z]$")),
        FormatRule("Civilian Classic", Regex("^K[A-Z]{2}\\s\\d{3}$")),
        FormatRule("Government (GK)", Regex("^GK\\s[A-Z]?\\d{3}[A-Z]?$")),
        FormatRule("Diplomatic / UN", Regex("^(CD|UN)\\s\\d{2,4}(\\s[A-Z]{1,2})?$")),
        FormatRule("EAC Regional", Regex("^(U[A-Z]{2}|T\\s?\\d{3}|RA[A-Z])\\s?\\d{3}[A-Z]{1,2}$")),
        FormatRule("International Standard", Regex("^[A-Z]{2,3}\\s\\d{2,4}(\\s[A-Z]{1,2})?$")),
        FormatRule("UK / Commonwealth", Regex("^[A-Z]{2}\\d{2}\\s[A-Z]{3}$")),
    )

    fun normalizeWithoutGuessing(rawText: String): String? {
        val cleaned = rawText.uppercase()
            .replace(Regex("[^A-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length !in 5..11) return null

        // Reject if any token is a known vehicle brand, road sign word, or speed limit indicator
        val tokens = cleaned.split(" ").filter { it.isNotBlank() }
        if (tokens.any { it in NON_PLATE_WORDS }) return null

        // A valid registration plate must contain at least 2 letters and at least 2 digits
        val letterCount = cleaned.count { it.isLetter() }
        val digitCount = cleaned.count { it.isDigit() }
        if (letterCount < 2 || digitCount < 2) return null

        // Insert single canonical space between prefix and digits if omitted (e.g. KDA123A -> KDA 123A)
        val compact = cleaned.replace(" ", "")
        return when {
            compact.matches(Regex("^K[A-Z]{2}\\d{3}[A-Z]$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            compact.matches(Regex("^K[A-Z]{2}\\d{3}$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            compact.matches(Regex("^GK[A-Z]?\\d{3}[A-Z]?$")) -> "GK ${compact.substring(2)}"
            compact.matches(Regex("^U[A-Z]{2}\\d{3}[A-Z]$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            compact.matches(Regex("^[A-Z]{2}\\d{2}[A-Z]{3}$")) -> "${compact.substring(0, 4)} ${compact.substring(4)}"
            compact.matches(Regex("^[A-Z]{3}\\d{3,4}[A-Z]?$")) -> "${compact.substring(0, 3)} ${compact.substring(3)}"
            else -> cleaned
        }
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

    fun matchesWatchedPlate(normalizedPlate: String, watchedPlates: List<String>): String? {
        val compactTarget = normalizedPlate.uppercase().replace(" ", "")
        return watchedPlates.firstOrNull { watched ->
            watched.uppercase().replace(" ", "") == compactTarget
        }
    }

    /**
     * Formats a plate for TTS so every letter is pronounced individually with full stops
     * (e.g. "KDA 123A" -> "K. D. A. 1 2 3 A.") instead of reading letter groups as one word.
     */
    fun formatForSpeech(plate: String): String {
        val cleaned = plate.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (cleaned.isEmpty()) return ""
        return buildString {
            cleaned.forEachIndexed { index, ch ->
                if (ch.isLetter()) {
                    append(ch)
                    append('.')
                } else {
                    append(ch)
                }
                if (index < cleaned.lastIndex) {
                    append(' ')
                }
            }
        }
    }
}

/**
 * Pipeline C — Offline Registration Plate Recognition:
 * - Locates white-background (front) and yellow-background (rear) rectangular plate candidates
 *   using geometry, background color, and vertical character-stroke edge density.
 * - Applies tilt deskewing and adaptive contrast normalization to plate crops.
 * - Validates character arrangement against configurable country rules without guessing characters.
 * - Tracks multiple plate candidates across frames and returns the most consistent reading.
 */
@Singleton
class PlateRecognitionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class PlateTrack(
        var hits: Int = 0,
        var bestConfidence: Float = 0f,
        var bestQualityScore: Float = 0f,
        var formatCategory: String = "",
        var surfaceColor: PlateSurfaceColor = PlateSurfaceColor.OTHER_CONTRAST,
        var lastSeenMs: Long = 0L,
    )

    private val activePlateTracks = LinkedHashMap<String, PlateTrack>()

    /**
     * Locates rectangular white or yellow number-plate regions inside or near detected vehicles
     * and in the forward road lane, preprocesses (deskews + contrast-enhances) each crop for offline OCR.
     */
    fun locateAndPreparePlateCandidates(
        bitmap: Bitmap,
        trackedVehicles: List<TrackedRoadObject> = emptyList(),
        maxCandidates: Int = 2,
    ): List<PlateRegionCandidate> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 120 || h < 120) return emptyList()

        val candidates = mutableListOf<PlateRegionCandidate>()

        // 1. Search lower half of detected vehicle bounding boxes (front or rear bumper plate zone)
        for (veh in trackedVehicles) {
            val isVehicle = veh.label.contains("Car", ignoreCase = true) ||
                veh.label.contains("Bus", ignoreCase = true) ||
                veh.label.contains("Lorry", ignoreCase = true) ||
                veh.label.contains("Truck", ignoreCase = true) ||
                veh.label.contains("Vehicle", ignoreCase = true)
            if (!isVehicle) continue

            val vLeft = (veh.normalizedLeft * w).toInt().coerceIn(0, w - 1)
            val vRight = (veh.normalizedRight * w).toInt().coerceIn(vLeft + 1, w)
            val vTop = (veh.normalizedTop * h).toInt().coerceIn(0, h - 1)
            val vBot = (veh.normalizedBottom * h).toInt().coerceIn(vTop + 1, h)
            val vw = vRight - vLeft
            val vh = vBot - vTop
            if (vw < 56 || vh < 44) continue

            val searchTop = (vTop + vh * 0.42f).toInt().coerceIn(0, h - 1)
            val searchRect = Rect(vLeft, searchTop, vRight, vBot)
            findBestPlateSubRect(bitmap, searchRect)?.let { candidates.add(it) }
        }

        // 2. Fallback: scan central/lower road corridor for white or yellow high-contrast rectangular plates
        if (candidates.isEmpty()) {
            val corridorRect = Rect(
                (w * 0.14f).toInt(),
                (h * 0.42f).toInt(),
                (w * 0.86f).toInt(),
                (h * 0.90f).toInt(),
            )
            findBestPlateSubRect(bitmap, corridorRect)?.let { candidates.add(it) }
        }

        return candidates
            .sortedByDescending { it.edgeScore }
            .take(maxCandidates)
    }

    private fun findBestPlateSubRect(bitmap: Bitmap, searchRect: Rect): PlateRegionCandidate? {
        val sw = searchRect.width()
        val sh = searchRect.height()
        if (sw < 48 || sh < 18) return null

        val gridCols = 16
        val gridRows = 10
        val cellW = (sw.toFloat() / gridCols).coerceAtLeast(1f)
        val cellH = (sh.toFloat() / gridRows).coerceAtLeast(1f)

        var bestRect: Rect? = null
        var bestSurface = PlateSurfaceColor.OTHER_CONTRAST
        var bestScore = 0f

        for (r in 1 until gridRows - 1) {
            val y0 = (searchRect.top + r * cellH).toInt().coerceIn(0, bitmap.height - 1)
            val y1 = (y0 + cellH * 2.2f).toInt().coerceIn(y0 + 1, bitmap.height)
            for (c in 1 until gridCols - 3) {
                val x0 = (searchRect.left + c * cellW).toInt().coerceIn(0, bitmap.width - 1)
                val x1 = (x0 + cellW * 4.5f).toInt().coerceIn(x0 + 1, bitmap.width)
                val candRect = Rect(x0, y0, x1, y1)
                val rw = candRect.width()
                val rh = candRect.height()
                val aspect = rw.toFloat() / rh.toFloat().coerceAtLeast(1f)
                if (rw < 40 || rh < 12 || aspect !in 1.8f..5.6f) continue

                val quality = assessCropQuality(bitmap, candRect)
                if (!quality.isAcceptable) continue
                val surfaceBonus = when (quality.surfaceColor) {
                    PlateSurfaceColor.WHITE_BLACK_TEXT, PlateSurfaceColor.YELLOW_BLACK_TEXT -> 12f
                    PlateSurfaceColor.OTHER_CONTRAST -> 0f
                }
                val score = quality.sharpnessScore + quality.contrastStdDev * 0.5f + surfaceBonus
                if (score > bestScore) {
                    bestScore = score
                    bestRect = candRect
                    bestSurface = quality.surfaceColor
                }
            }
        }

        val chosen = bestRect ?: return null
        val rawCrop = Bitmap.createBitmap(bitmap, chosen.left, chosen.top, chosen.width(), chosen.height())
        val enhancedCrop = preprocessAndDeskewPlateCrop(rawCrop)
        return PlateRegionCandidate(
            rect = chosen,
            crop = enhancedCrop,
            surfaceColor = bestSurface,
            edgeScore = bestScore,
        )
    }

    /**
     * Deskews slight horizontal tilt (-12..+12 deg), upscales small plate crops,
     * and applies percentile contrast normalization so black characters on white or yellow plates
     * are sharp for offline ML Kit OCR.
     */
    fun preprocessAndDeskewPlateCrop(crop: Bitmap): Bitmap {
        val targetW = if (crop.width < 220) (crop.width * 2).coerceAtMost(440) else crop.width
        val targetH = if (crop.width < 220) (crop.height * 2).coerceAtMost(160) else crop.height
        val scaled = if (targetW != crop.width || targetH != crop.height) {
            Bitmap.createScaledBitmap(crop, targetW.coerceAtLeast(48), targetH.coerceAtLeast(16), true)
        } else {
            crop
        }

        // Estimate tilt from dark character centroids on left vs right halves of the plate
        val skewDeg = estimatePlateTiltDegrees(scaled)
        val deskewed = if (abs(skewDeg) in 1.5f..14.0f) {
            val matrix = Matrix().apply { postRotate(-skewDeg, scaled.width * 0.5f, scaled.height * 0.5f) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        } else {
            scaled
        }

        val w = deskewed.width
        val h = deskewed.height
        val pixels = IntArray(w * h)
        deskewed.getPixels(pixels, 0, w, 0, 0, w, h)

        var minLum = 255f
        var maxLum = 0f
        for (px in pixels) {
            val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }
        val range = (maxLum - minLum).coerceAtLeast(25f)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (((Color.red(px) - minLum) * 255f) / range).toInt().coerceIn(0, 255)
            val g = (((Color.green(px) - minLum) * 255f) / range).toInt().coerceIn(0, 255)
            val b = (((Color.blue(px) - minLum) * 255f) / range).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun estimatePlateTiltDegrees(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 12) return 0f
        var leftSumY = 0f
        var leftCount = 0
        var rightSumY = 0f
        var rightCount = 0
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        for (y in (h / 5) until (h * 4 / 5) step stepY) {
            for (x in (w / 6) until (w * 5 / 6) step stepX) {
                val px = bitmap.getPixel(x, y)
                val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
                if (lum < 95f) {
                    if (x < w / 2) {
                        leftSumY += y
                        leftCount++
                    } else {
                        rightSumY += y
                        rightCount++
                    }
                }
            }
        }
        if (leftCount < 3 || rightCount < 3) return 0f
        val dy = (rightSumY / rightCount) - (leftSumY / leftCount)
        val dx = w * 0.35f
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat().coerceIn(-14f, 14f)
    }

    fun assessCropQuality(bitmap: Bitmap, box: Rect): PlateQualityAssessment {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 36 || h < 11) {
            return PlateQualityAssessment(0f, 0f, 0f, false, "Plate crop too small (${w}x${h})")
        }

        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 6).coerceAtLeast(1)
        val lums = mutableListOf<Float>()
        var edgeSum = 0f
        var edgeCount = 0
        var whiteBgHits = 0
        var yellowBgHits = 0
        var darkCharHits = 0
        var totalSamples = 0

        var y = top
        while (y < bottom) {
            var prevLum: Float? = null
            var x = left
            while (x < right) {
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val spread = maxOf(r, g, b) - minOf(r, g, b)
                lums.add(lum)
                totalSamples++

                if (lum >= 145f && spread <= 52) {
                    whiteBgHits++
                } else if (r >= 135 && g >= 110 && b <= 115 && (r - b) >= 45 && (g - b) >= 30) {
                    yellowBgHits++
                } else if (lum <= 95f) {
                    darkCharHits++
                }

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

        val surfaceColor = when {
            totalSamples > 0 && yellowBgHits.toFloat() / totalSamples >= 0.18f && darkCharHits >= 2 ->
                PlateSurfaceColor.YELLOW_BLACK_TEXT
            totalSamples > 0 && whiteBgHits.toFloat() / totalSamples >= 0.22f && darkCharHits >= 2 ->
                PlateSurfaceColor.WHITE_BLACK_TEXT
            else -> PlateSurfaceColor.OTHER_CONTRAST
        }

        val acceptable = mean in 30f..240f && stdDev >= 14f && sharpness >= 7.5f
        val reason = when {
            mean < 30f -> "Too dark"
            mean > 240f -> "Overexposed glare"
            stdDev < 14f -> "Low contrast"
            sharpness < 7.5f -> "Motion blur"
            else -> "Clear (${surfaceColor.label})"
        }
        return PlateQualityAssessment(mean, stdDev, sharpness, acceptable, reason, surfaceColor)
    }

    @Synchronized
    fun evaluateOcrBlock(
        bitmap: Bitmap,
        block: Text.TextBlock,
        timestampMillis: Long,
    ): PlateObservation? {
        val box = block.boundingBox ?: return null
        val (normalized, formatName) = KenyanPlateValidator.validateFormat(block.text)
            ?: block.lines.firstNotNullOfOrNull { KenyanPlateValidator.validateFormat(it.text) }
            ?: return null

        val quality = assessCropQuality(bitmap, box)
        if (!quality.isAcceptable) return null

        // Evaluate ML Kit symbol/element confidence if reported; reject uncertain characters
        val elementConfidences = block.lines.flatMap { it.elements }.mapNotNull { it.confidence }
        val surfaceBoost = when (quality.surfaceColor) {
            PlateSurfaceColor.WHITE_BLACK_TEXT, PlateSurfaceColor.YELLOW_BLACK_TEXT -> 0.06f
            PlateSurfaceColor.OTHER_CONTRAST -> 0f
        }
        val avgConf = if (elementConfidences.isNotEmpty()) {
            (elementConfidences.average().toFloat() + surfaceBoost).coerceIn(0f, 0.98f)
        } else {
            (0.65f + (quality.sharpnessScore / 60f) + surfaceBoost).coerceIn(0.65f, 0.94f)
        }
        if (avgConf < MIN_OCR_CONFIDENCE) return null

        return recordAndConfirmPlateReading(
            normalized = normalized,
            formatName = formatName,
            ocrConfidence = avgConf,
            qualityScore = (quality.sharpnessScore / 40f).coerceIn(0f, 1f),
            surfaceColor = quality.surfaceColor,
            timestampMillis = timestampMillis,
        )
    }

    @Synchronized
    internal fun recordAndConfirmPlateReading(
        normalized: String,
        formatName: String,
        ocrConfidence: Float,
        qualityScore: Float,
        surfaceColor: PlateSurfaceColor,
        timestampMillis: Long,
    ): PlateObservation? {
        // Prune stale plate tracks older than CONSISTENCY_WINDOW_MS
        val iter = activePlateTracks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if ((timestampMillis - entry.value.lastSeenMs) > CONSISTENCY_WINDOW_MS) {
                iter.remove()
            }
        }

        val track = activePlateTracks.getOrPut(normalized) { PlateTrack() }
        track.hits += 1
        track.bestConfidence = maxOf(track.bestConfidence, ocrConfidence)
        track.bestQualityScore = maxOf(track.bestQualityScore, qualityScore)
        track.formatCategory = formatName
        if (surfaceColor != PlateSurfaceColor.OTHER_CONTRAST) {
            track.surfaceColor = surfaceColor
        }
        track.lastSeenMs = timestampMillis

        // Select the most consistent plate across recent frames that meets REQUIRED_CONSISTENT_FRAMES
        val bestConsistent = activePlateTracks.entries
            .filter { (_, t) -> t.hits >= REQUIRED_CONSISTENT_FRAMES }
            .maxWithOrNull(compareBy<Map.Entry<String, PlateTrack>> { it.value.hits }.thenBy { it.value.bestConfidence })
            ?: return null

        val (bestPlate, bestTrack) = bestConsistent
        return PlateObservation(
            rawNormalizedPlate = bestPlate,
            maskedPlate = KenyanPlateValidator.maskForDisplay(bestPlate),
            formatCategory = bestTrack.formatCategory,
            ocrConfidence = bestTrack.bestConfidence,
            qualityScore = bestTrack.bestQualityScore,
            consecutiveFrames = bestTrack.hits,
            timestampMillis = timestampMillis,
            surfaceColor = bestTrack.surfaceColor,
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
                "surface=${observation.surfaceColor.name}\n" +
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
        activePlateTracks.clear()
    }

    companion object {
        const val MIN_OCR_CONFIDENCE = 0.60f
        const val REQUIRED_CONSISTENT_FRAMES = 2
        const val CONSISTENCY_WINDOW_MS = 4_000L

        fun extractRegistrationPlate(rawText: String): String? =
            KenyanPlateValidator.validateFormat(rawText)?.first
    }
}
