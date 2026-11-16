package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import ke.co.kopilot.core.common.model.TrafficSignCategory

object SignCandidateParser {

    fun parseSignFromText(
        rawText: String,
        hasSignBorderColor: Boolean,
        normalizedCenterX: Float,
    ): RawSignDetection? {
        val cleaned = rawText.uppercase().replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null

        val categoryAndConfidence: Pair<TrafficSignCategory, Float>? = when {
            cleaned == "STOP" || cleaned.contains(" STOP") ->
                TrafficSignCategory.STOP to if (hasSignBorderColor) 0.90f else 0.78f
            cleaned.contains("GIVE WAY") || cleaned.contains("YIELD") ->
                TrafficSignCategory.GIVE_WAY to 0.85f
            cleaned.contains("NO ENTRY") ->
                TrafficSignCategory.NO_ENTRY to 0.85f
            cleaned.contains("SCHOOL") ->
                TrafficSignCategory.SCHOOL_ZONE to 0.82f
            cleaned.contains("BUMP") || cleaned.contains("HUMP") ->
                TrafficSignCategory.SPEED_BUMP to 0.82f
            cleaned.contains("ROADWORK") || cleaned.contains("MEN AT WORK") ->
                TrafficSignCategory.ROADWORKS to 0.82f
            cleaned.contains("PEDESTRIAN") || cleaned.contains("CROSSING") ->
                TrafficSignCategory.PEDESTRIAN_CROSSING to 0.80f
            cleaned.contains("NO OVERTAKING") ->
                TrafficSignCategory.NO_OVERTAKING to 0.82f
            else -> {
                // Speed limits require red/yellow sign border color around the number to avoid false positives
                if (!hasSignBorderColor) return null
                when {
                    Regex("\\b30\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_30 to 0.86f
                    Regex("\\b50\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_50 to 0.88f
                    Regex("\\b60\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_60 to 0.86f
                    Regex("\\b80\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_80 to 0.88f
                    Regex("\\b100\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_100 to 0.88f
                    Regex("\\b110\\b").containsMatchIn(cleaned) -> TrafficSignCategory.SPEED_LIMIT_110 to 0.88f
                    else -> null
                }
            }
        }

        return categoryAndConfidence?.let { (cat, conf) ->
            RawSignDetection(
                category = cat,
                confidence = conf,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }
    }

    /**
     * Checks a ring around [box] for red or yellow sign border pixels.
     */
    fun hasSignBorderColor(bitmap: Bitmap, box: Rect): Boolean {
        val padX = (box.width() * 0.45f).toInt().coerceAtLeast(8)
        val padY = (box.height() * 0.45f).toInt().coerceAtLeast(8)
        val left = (box.left - padX).coerceIn(0, bitmap.width - 1)
        val top = (box.top - padY).coerceIn(0, bitmap.height - 1)
        val right = (box.right + padX).coerceIn(0, bitmap.width - 1)
        val bottom = (box.bottom + padY).coerceIn(0, bitmap.height - 1)

        var signColorHits = 0
        var sampled = 0
        val stepX = ((right - left) / 14).coerceAtLeast(1)
        val stepY = ((bottom - top) / 14).coerceAtLeast(1)

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                if (!box.contains(x, y)) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val isRed = r > 140 && g < 95 && b < 95 && (r - g) > 55
                    val isYellow = r > 160 && g > 130 && b < 90
                    if (isRed || isYellow) signColorHits++
                    sampled++
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (signColorHits.toFloat() / sampled.toFloat()) >= 0.06f
    }
}
