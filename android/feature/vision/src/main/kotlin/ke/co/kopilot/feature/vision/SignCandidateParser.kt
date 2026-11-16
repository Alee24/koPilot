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
        hasWhiteInterior: Boolean = true,
    ): RawSignDetection? {
        val rawUpper = rawText.uppercase().replace(Regex("\\s+"), " ").trim()
        if (rawUpper.isEmpty()) return null

        // Normalize common OCR letter/digit confusions on circular speed signs (e.g. "3O" -> "30")
        val speedNormalized = rawUpper
            .replace(Regex("\\b([1-9])O\\b"), "$10")
            .replace(Regex("\\b1OO\\b"), "100")
            .replace(Regex("\\b11O\\b"), "110")
            .replace(Regex("\\bB0\\b"), "80")

        val explicitSpeedText = rawUpper.contains("KM") ||
            rawUpper.contains("KPH") ||
            rawUpper.contains("SPEED") ||
            rawUpper.contains("LIMIT") ||
            rawUpper.contains("MAX")

        // Check numeric speed limits FIRST when white interior or red/yellow border or speed text is present
        val speedCategory = when {
            Regex("\\b30\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_30
            Regex("\\b50\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_50
            Regex("\\b60\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_60
            Regex("\\b80\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_80
            Regex("\\b100\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_100
            Regex("\\b110\\b").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_110
            else -> null
        }

        if (speedCategory != null && (hasSignBorderColor || explicitSpeedText || hasWhiteInterior)) {
            val confidence = when {
                hasSignBorderColor && hasWhiteInterior -> 0.92f
                hasSignBorderColor || explicitSpeedText -> 0.84f
                else -> 0.54f // < 60% unsure: triggers auto-zoom & "unsure" advisory
            }
            return RawSignDetection(
                category = speedCategory,
                confidence = confidence,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }

        val categoryAndConfidence: Pair<TrafficSignCategory, Float>? = when {
            rawUpper == "STOP" || (Regex("\\bSTOP\\b").containsMatchIn(rawUpper) && !hasWhiteInterior) ->
                TrafficSignCategory.STOP to if (hasSignBorderColor) 0.90f else 0.56f
            rawUpper.contains("GIVE WAY") || rawUpper.contains("YIELD") ->
                TrafficSignCategory.GIVE_WAY to if (hasSignBorderColor) 0.86f else 0.56f
            rawUpper.contains("NO ENTRY") ->
                TrafficSignCategory.NO_ENTRY to if (hasSignBorderColor) 0.88f else 0.56f
            rawUpper.contains("SCHOOL") || rawUpper.contains("CHILDREN") ->
                TrafficSignCategory.SCHOOL_ZONE to 0.84f
            rawUpper.contains("BUMP") || rawUpper.contains("HUMP") || rawUpper.contains("RUMBLE") ->
                TrafficSignCategory.SPEED_BUMP to 0.84f
            rawUpper.contains("ROADWORK") || rawUpper.contains("MEN AT WORK") || rawUpper.contains("CONSTRUCTION") ->
                TrafficSignCategory.ROADWORKS to 0.84f
            rawUpper.contains("PEDESTRIAN") || rawUpper.contains("CROSSING") || rawUpper.contains("ZEBRA") ->
                TrafficSignCategory.PEDESTRIAN_CROSSING to 0.82f
            rawUpper.contains("NO OVERTAKING") || rawUpper.contains("NO PASSING") ->
                TrafficSignCategory.NO_OVERTAKING to 0.84f
            rawUpper.contains("BEND") || rawUpper.contains("CURVE") ->
                TrafficSignCategory.DANGEROUS_BEND to 0.82f
            rawUpper.contains("ROUNDABOUT") ->
                TrafficSignCategory.ROUNDABOUT to 0.82f
            rawUpper.contains("RAILWAY") || rawUpper.contains("LEVEL CROSSING") ->
                TrafficSignCategory.RAILWAY_CROSSING to 0.84f
            rawUpper.contains("HOSPITAL") ->
                TrafficSignCategory.HOSPITAL_ZONE to 0.82f
            rawUpper.contains("BLACKSPOT") || rawUpper.contains("BLACK SPOT") ->
                TrafficSignCategory.BLACKSPOT to 0.85f
            rawUpper.contains("POLICE") || rawUpper.contains("CHECKPOINT") ->
                TrafficSignCategory.POLICE_CHECKPOINT to 0.82f
            hasSignBorderColor && rawUpper.length in 3..26 ->
                TrafficSignCategory.GENERAL_WARNING to 0.55f
            else -> null
        }

        return categoryAndConfidence?.let { (cat, conf) ->
            RawSignDetection(
                category = cat,
                confidence = conf,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
                customLabel = if (cat == TrafficSignCategory.GENERAL_WARNING) rawUpper else null,
            )
        }
    }

    /**
     * Checks whether the interior of [box] is predominantly white/bright (typical of speed limit signs)
     * rather than solid red (typical of STOP / No Entry octagons).
     */
    fun hasWhiteInterior(bitmap: Bitmap, box: Rect): Boolean {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(0, bitmap.width - 1)
        val bottom = box.bottom.coerceIn(0, bitmap.height - 1)
        val stepX = ((right - left) / 8).coerceAtLeast(1)
        val stepY = ((bottom - top) / 8).coerceAtLeast(1)

        var whiteHits = 0
        var sampled = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                if (r > 155 && g > 155 && b > 155) whiteHits++
                sampled++
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (whiteHits.toFloat() / sampled) >= 0.25f
    }

    /**
     * Checks a ring around [box] for red or yellow sign border pixels.
     */
    fun hasSignBorderColor(bitmap: Bitmap, box: Rect): Boolean {
        val padX = (box.width() * 0.50f).toInt().coerceAtLeast(10)
        val padY = (box.height() * 0.50f).toInt().coerceAtLeast(10)
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
                    val isRed = r > 135 && g < 95 && b < 95 && (r - g) > 50
                    val isYellow = r > 155 && g > 125 && b < 90
                    if (isRed || isYellow) signColorHits++
                    sampled++
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (signColorHits.toFloat() / sampled.toFloat()) >= 0.05f
    }
}
