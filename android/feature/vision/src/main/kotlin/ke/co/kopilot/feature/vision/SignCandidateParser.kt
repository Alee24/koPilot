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

        // Never treat Kenyan vehicle registration plates (e.g. "KDM", "KDM 632A", "KCA456B", "GK A123") as road signs
        val compact = rawUpper.replace(" ", "")
        if (Regex("^K[A-Z]{2}(\\d{3}[A-Z]?)?$").matches(compact) ||
            Regex("^(GK|CD|UN)[A-Z]?\\d{2,4}[A-Z]?$").matches(compact) ||
            FrontRoadAnalyzer.extractKenyanPlate(rawUpper) != null
        ) {
            return null
        }

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

        // Check numeric speed limits FIRST using lookarounds so "50KM", "50KPH", "50" all match
        val speedCategory = when {
            Regex("(?<!\\d)30(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_30
            Regex("(?<!\\d)50(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_50
            Regex("(?<!\\d)60(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_60
            Regex("(?<!\\d)80(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_80
            Regex("(?<!\\d)100(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_100
            Regex("(?<!\\d)110(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_110
            else -> null
        }

        if (speedCategory != null) {
            val confidence = when {
                hasSignBorderColor && hasWhiteInterior -> 0.92f
                hasSignBorderColor || explicitSpeedText -> 0.86f
                hasWhiteInterior -> 0.72f
                else -> 0.54f // < 60% unsure: still reports the sign, triggers auto-zoom & "unsure" advisory
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
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val spread = maxOf(r, g, b) - minOf(r, g, b)
                if (lum > 115f && spread < 60) whiteHits++
                sampled++
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (whiteHits.toFloat() / sampled) >= 0.20f
    }

    /**
     * Checks a ring around [box] for red, yellow, or blue sign border pixels.
     */
    fun hasSignBorderColor(bitmap: Bitmap, box: Rect): Boolean {
        val padX = (box.width() * 0.55f).toInt().coerceAtLeast(12)
        val padY = (box.height() * 0.55f).toInt().coerceAtLeast(12)
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
                    if (SignRoiExtractor.classifySignPixel(pixel) != null) signColorHits++
                    sampled++
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (signColorHits.toFloat() / sampled.toFloat()) >= 0.03f
    }
}
