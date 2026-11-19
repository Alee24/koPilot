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
        isOnVehicleBody: Boolean = false,
    ): RawSignDetection? {
        val rawUpper = rawText.uppercase().replace(Regex("\\s+"), " ").trim()
        if (rawUpper.isEmpty()) return null

        // Filter out roadside billboards, commercial advertisements, phone numbers, and distances
        val wordCount = rawUpper.split(" ").count { it.isNotBlank() }
        if (wordCount > 5 || rawUpper.length > 36 || BILLBOARD_KEYWORDS.any { rawUpper.contains(it) }) {
            return null
        }

        // Never treat vehicle registration plates (Kenyan or international formats) as road signs
        val compact = rawUpper.replace(" ", "").replace("-", "")
        if (Regex("^K[A-Z]{2}(\\d{3}[A-Z]?)?$").matches(compact) ||
            Regex("^(GK|CD|UN)[A-Z]?\\d{2,4}[A-Z]?$").matches(compact) ||
            Regex("^[A-Z]{2,3}\\d{2,4}[A-Z]{0,2}$").matches(compact) ||
            Regex("^\\d{2,4}[A-Z]{2,3}$").matches(compact) ||
            FrontRoadAnalyzer.extractKenyanPlate(rawUpper) != null ||
            PlateRecognitionPipeline.extractRegistrationPlate(rawUpper) != null
        ) {
            return null
        }

        // Reject distance markers (e.g. "50M", "100M", "200 M"), highway route numbers ("A104", "B50"), and percentages ("10%")
        if (Regex("\\b\\d{1,3}\\s?(M|METRES|METERS)\\b").containsMatchIn(rawUpper) ||
            Regex("\\b\\d{1,3}\\s?%").containsMatchIn(rawUpper) ||
            Regex("\\b[ABCDE]\\s?\\d{1,3}\\b").containsMatchIn(rawUpper)
        ) {
            return null
        }

        // Normalize common OCR letter/digit confusions on circular speed signs (e.g. "3O" -> "30")
        val speedNormalized = rawUpper
            .replace(Regex("\\b([1-9])O\\b"), "$10")
            .replace(Regex("\\b1OO\\b"), "100")
            .replace(Regex("\\b11O\\b"), "110")
            .replace(Regex("\\b12O\\b"), "120")
            .replace(Regex("\\bB0\\b"), "80")

        val explicitSpeedText = rawUpper.contains("KM") ||
            rawUpper.contains("KPH") ||
            rawUpper.contains("SPEED") ||
            rawUpper.contains("LIMIT") ||
            rawUpper.contains("MAX")

        // Check numeric speed limits FIRST using lookarounds so "50KM", "50KPH", "50" all match
        val speedCategory = when {
            Regex("(?<!\\d)10(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_10
            Regex("(?<!\\d)20(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_20
            Regex("(?<!\\d)30(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_30
            Regex("(?<!\\d)40(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_40
            Regex("(?<!\\d)50(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_50
            Regex("(?<!\\d)60(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_60
            Regex("(?<!\\d)70(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_70
            Regex("(?<!\\d)80(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_80
            Regex("(?<!\\d)90(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_90
            Regex("(?<!\\d)100(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_100
            Regex("(?<!\\d)110(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_110
            Regex("(?<!\\d)120(?!\\d)").containsMatchIn(speedNormalized) -> TrafficSignCategory.SPEED_LIMIT_120
            else -> null
        }

        if (speedCategory != null) {
            // Reject speed stickers painted on the rear of buses, matatus, and commercial trucks
            if (isOnVehicleBody) return null

            // Dedicated speed-limit sign verification:
            // Must have either a verified sign border color (red circle/ring) or explicit speed-limit text ("50 KM/H") on a white sign face.
            // Bare numbers on a plain white surface without a red sign border are marked uncertain (< 0.60f) so white trucks/walls never trigger false speed limits.
            val confidence = when {
                hasSignBorderColor && hasWhiteInterior -> 0.92f
                hasSignBorderColor && explicitSpeedText -> 0.88f
                explicitSpeedText && hasWhiteInterior -> 0.84f
                hasSignBorderColor -> 0.78f
                else -> 0.54f // < 60% uncertain: requires border color or explicit speed text to confirm
            }
            return RawSignDetection(
                category = speedCategory,
                confidence = confidence,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }

        if (isOnVehicleBody) return null

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
                TrafficSignCategory.ROADWORKS to if (hasSignBorderColor) 0.86f else 0.76f
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
            rawUpper.contains("KEEP LEFT") ->
                TrafficSignCategory.KEEP_LEFT to 0.84f
            rawUpper.contains("ONE WAY") ->
                TrafficSignCategory.ONE_WAY to 0.84f
            rawUpper.contains("NO U-TURN") || rawUpper.contains("NO U TURN") ->
                TrafficSignCategory.NO_U_TURN to 0.84f
            rawUpper.contains("NO PARKING") ->
                TrafficSignCategory.NO_PARKING to 0.84f
            rawUpper.contains("NARROW BRIDGE") ->
                TrafficSignCategory.NARROW_BRIDGE to 0.82f
            rawUpper.contains("STEEP") || rawUpper.contains("GRADIENT") ->
                TrafficSignCategory.STEEP_HILL to 0.80f
            rawUpper.contains("SLIPPERY") ->
                TrafficSignCategory.SLIPPERY_ROAD to 0.80f
            rawUpper.contains("CATTLE") || rawUpper.contains("WILDLIFE") || rawUpper.contains("ANIMAL") ->
                TrafficSignCategory.ANIMAL_CROSSING to 0.80f
            rawUpper.contains("TRAFFIC SIGNAL") || rawUpper.contains("TRAFFIC LIGHT") ->
                TrafficSignCategory.TRAFFIC_SIGNALS_AHEAD to 0.82f
            rawUpper.contains("END OF") && rawUpper.contains("LIMIT") ->
                TrafficSignCategory.END_SPEED_LIMIT to 0.80f
            hasSignBorderColor && rawUpper.length in 4..22 ->
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
     * Checks whether an extracted ROI crop actually has a red, yellow, or blue outer sign border ring
     * surrounding its center (preventing large non-sign crops from bypassing border verification).
     */
    fun hasRoiPerimeterSignColor(roiBitmap: Bitmap): Boolean {
        val w = roiBitmap.width
        val h = roiBitmap.height
        if (w < 12 || h < 12) return false
        var borderColorHits = 0
        var sampled = 0
        val steps = 12
        for (i in 0 until steps) {
            val x = ((i + 0.5f) * w / steps).toInt().coerceIn(0, w - 1)
            val y = ((i + 0.5f) * h / steps).toInt().coerceIn(0, h - 1)
            val topY = (h * 0.14f).toInt().coerceIn(0, h - 1)
            val botY = (h * 0.86f).toInt().coerceIn(0, h - 1)
            val leftX = (w * 0.14f).toInt().coerceIn(0, w - 1)
            val rightX = (w * 0.86f).toInt().coerceIn(0, w - 1)

            if (SignRoiExtractor.classifySignPixel(roiBitmap.getPixel(x, topY)) != null) borderColorHits++
            if (SignRoiExtractor.classifySignPixel(roiBitmap.getPixel(x, botY)) != null) borderColorHits++
            if (SignRoiExtractor.classifySignPixel(roiBitmap.getPixel(leftX, y)) != null) borderColorHits++
            if (SignRoiExtractor.classifySignPixel(roiBitmap.getPixel(rightX, y)) != null) borderColorHits++
            sampled += 4
        }
        return sampled > 0 && (borderColorHits.toFloat() / sampled) >= 0.12f
    }

    /**
     * Checks whether a text bounding box overlaps a detected vehicle body (bus, truck, car)
     * so rear speed-limit stickers ("80 KPH") or commercial vehicle markings are rejected.
     */
    fun isBoxInsideDetectedVehicle(
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        trackedVehicles: List<TrackedRoadObject>,
    ): Boolean {
        val cx = (normLeft + normRight) * 0.5f
        val cy = (normTop + normBottom) * 0.5f
        return trackedVehicles.any { v ->
            val isVehicle = v.label.contains("Car", ignoreCase = true) ||
                v.label.contains("Bus", ignoreCase = true) ||
                v.label.contains("Lorry", ignoreCase = true) ||
                v.label.contains("Truck", ignoreCase = true) ||
                v.label.contains("Vehicle", ignoreCase = true) ||
                v.label.contains("Tractor", ignoreCase = true)
            isVehicle &&
                cx in (v.normalizedLeft + 0.02f)..(v.normalizedRight - 0.02f) &&
                cy in (v.normalizedTop + 0.02f)..(v.normalizedBottom - 0.02f)
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

    private val BILLBOARD_KEYWORDS = listOf(
        "MPESA", "M-PESA", "SAFARICOM", "AIRTEL", "EQUITY", "KCB", "CO-OP", "ABSA",
        "HOTEL", "OFFER", "WWW.", ".CO.KE", ".COM", "LOAN", "CHURCH", "SUPERMARKET",
        "APARTMENT", "FOR SALE", "TO LET", "CALL ", "TEL:", "BUY ",
    )
}
