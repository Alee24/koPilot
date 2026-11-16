package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color

enum class TrafficLightSignal {
    NONE,
    RED,
    GREEN,
}

data class FrameLightingAndSignals(
    val averageLuminance: Float,
    val isDark: Boolean,
    val redBrakeLightsAhead: Boolean,
    val trafficLightSignal: TrafficLightSignal,
)

object FrontRoadAnalyzer {

    private val kenyanPlateRegex = Regex("\\b(K[A-Z]{2}\\s?\\d{3}[A-Z]|GK\\s?[A-Z]?\\d{3}[A-Z]?)\\b")

    fun extractKenyanPlate(text: String): String? {
        val cleaned = text.uppercase().replace(Regex("\\s+"), " ").trim()
        return kenyanPlateRegex.find(cleaned)?.value
    }

    fun classifyObjectByGeometry(
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        mlKitLabel: String,
    ): String {
        val width = (normRight - normLeft).coerceAtLeast(0.01f)
        val height = (normBottom - normTop).coerceAtLeast(0.01f)
        val ratio = height / width
        val centerY = (normTop + normBottom) / 2f

        return when {
            mlKitLabel.equals("Person", ignoreCase = true) || (ratio >= 1.45f && centerY >= 0.25f) ->
                "Pedestrian"
            width >= 0.14f && ratio in 0.45f..1.35f && centerY >= 0.30f ->
                "Vehicle"
            width < 0.14f && height < 0.16f && centerY >= 0.55f ->
                "Animal / Obstacle"
            mlKitLabel.isNotBlank() && mlKitLabel != "Object" ->
                mlKitLabel
            else ->
                "Road Object"
        }
    }

    fun analyzeLightingAndLights(bitmap: Bitmap): FrameLightingAndSignals {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) {
            return FrameLightingAndSignals(128f, isDark = false, redBrakeLightsAhead = false, TrafficLightSignal.NONE)
        }

        val stepX = (w / 40).coerceAtLeast(1)
        val stepY = (h / 30).coerceAtLeast(1)

        var lumSum = 0f
        var lumCount = 0
        var brakeRedHits = 0
        var brakeSampled = 0
        var upperRedHits = 0
        var upperGreenHits = 0
        var upperSampled = 0

        var y = 0
        while (y < h) {
            val normY = y.toFloat() / h
            var x = 0
            while (x < w) {
                val normX = x.toFloat() / w
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                lumSum += lum
                lumCount++

                val isBrightRed = r >= 195 && g <= 80 && b <= 80 && (r - g) >= 115
                val isBrightGreen = g >= 185 && r <= 105 && b >= 90 && (g - r) >= 75

                // Middle road corridor: potential vehicle brake lights ahead
                if (normY in 0.38f..0.78f && normX in 0.22f..0.78f) {
                    if (isBrightRed) brakeRedHits++
                    brakeSampled++
                }
                // Upper road corridor: potential traffic signals / streetlights
                if (normY in 0.06f..0.40f && normX in 0.18f..0.82f) {
                    if (isBrightRed) upperRedHits++
                    if (isBrightGreen) upperGreenHits++
                    upperSampled++
                }
                x += stepX
            }
            y += stepY
        }

        val avgLum = if (lumCount > 0) lumSum / lumCount else 128f
        val brakeRatio = if (brakeSampled > 0) brakeRedHits.toFloat() / brakeSampled else 0f
        val upperRedRatio = if (upperSampled > 0) upperRedHits.toFloat() / upperSampled else 0f
        val upperGreenRatio = if (upperSampled > 0) upperGreenHits.toFloat() / upperSampled else 0f

        val tlSignal = when {
            upperRedRatio >= 0.012f && upperRedRatio > upperGreenRatio -> TrafficLightSignal.RED
            upperGreenRatio >= 0.015f && upperGreenRatio > upperRedRatio -> TrafficLightSignal.GREEN
            else -> TrafficLightSignal.NONE
        }

        return FrameLightingAndSignals(
            averageLuminance = avgLum,
            isDark = avgLum < DARKNESS_LUMINANCE_THRESHOLD,
            redBrakeLightsAhead = brakeRatio >= 0.018f,
            trafficLightSignal = tlSignal,
        )
    }

    const val DARKNESS_LUMINANCE_THRESHOLD = 48f
}
