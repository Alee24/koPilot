package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color

enum class TrafficLightSignal {
    NONE,
    RED,
    AMBER,
    GREEN,
}

data class BrakeLightSpot(
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
)

data class FrameLightingAndSignals(
    val averageLuminance: Float,
    val isDark: Boolean,
    val redBrakeLightsAhead: Boolean,
    val trafficLightSignal: TrafficLightSignal,
    val brakeLightBoxes: List<BrakeLightSpot> = emptyList(),
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
        val lowerLabel = mlKitLabel.lowercase()

        return when {
            lowerLabel.contains("person") || (ratio >= 1.75f && centerY >= 0.25f) ->
                "Pedestrian"
            lowerLabel.contains("bicycle") || (width in 0.04f..0.11f && ratio in 1.15f..1.74f && centerY >= 0.35f) ->
                "Bicycle"
            lowerLabel.contains("motorcycle") || lowerLabel.contains("motorbike") || (width in 0.08f..0.16f && ratio in 1.05f..1.74f && centerY >= 0.35f) ->
                "Motorbike"
            lowerLabel.contains("truck") || lowerLabel.contains("bus") || (width >= 0.30f && height >= 0.32f && centerY >= 0.25f) ->
                "Lorry / Bus"
            width in 0.20f..0.38f && ratio in 0.95f..1.35f && normTop < 0.35f ->
                "Tractor"
            width in 0.14f..0.38f && ratio in 0.45f..1.05f && centerY >= 0.30f ->
                "Car"
            width < 0.14f && height < 0.16f && centerY >= 0.55f ->
                "Animal / Obstacle"
            mlKitLabel.isNotBlank() && mlKitLabel != "Object" ->
                mlKitLabel
            else ->
                "Vehicle"
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
        val redGridCols = 40
        val redGridRows = 30
        val redGrid = Array(redGridRows) { BooleanArray(redGridCols) }
        val tlRedGrid = Array(redGridRows) { BooleanArray(redGridCols) }
        val tlAmberGrid = Array(redGridRows) { BooleanArray(redGridCols) }
        val tlGreenGrid = Array(redGridRows) { BooleanArray(redGridCols) }
        val darkGrid = Array(redGridRows) { BooleanArray(redGridCols) }

        var row = 0
        var y = 0
        while (y < h) {
            val normY = y.toFloat() / h
            var col = 0
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

                val isBrightRed = r >= 170 && g <= 115 && b <= 115 && (r - g) >= 80 && (r - b) >= 80
                val isBrightAmber = r >= 200 && g in 125..210 && b <= 75 && (r - b) >= 120
                val isBrightGreen = g >= 185 && r <= 100 && (g - r) >= 85 && g >= b

                if (row < redGridRows && col < redGridCols && lum < 95f) {
                    darkGrid[row][col] = true
                }

                // Middle road corridor: potential vehicle brake lights ahead
                if (normY in 0.30f..0.85f && normX in 0.12f..0.88f) {
                    if (isBrightRed) {
                        brakeRedHits++
                        if (row < redGridRows && col < redGridCols) {
                            redGrid[row][col] = true
                        }
                    }
                    brakeSampled++
                }
                // Upper road corridor: potential traffic signals (clustered bulbs with dark housing)
                if (normY in 0.04f..0.48f && normX in 0.10f..0.90f && row < redGridRows && col < redGridCols) {
                    if (isBrightRed) tlRedGrid[row][col] = true
                    if (isBrightAmber) tlAmberGrid[row][col] = true
                    if (isBrightGreen) tlGreenGrid[row][col] = true
                }
                col++
                x += stepX
            }
            row++
            y += stepY
        }

        val brakeBoxes = clusterRedSpots(redGrid, redGridRows, redGridCols)
        val hasRedTl = hasTrafficLightBulbCluster(tlRedGrid, darkGrid, redGridRows, redGridCols)
        val hasAmberTl = hasTrafficLightBulbCluster(tlAmberGrid, darkGrid, redGridRows, redGridCols)
        val hasGreenTl = hasTrafficLightBulbCluster(tlGreenGrid, darkGrid, redGridRows, redGridCols)

        val avgLum = if (lumCount > 0) lumSum / lumCount else 128f
        val brakeRatio = if (brakeSampled > 0) brakeRedHits.toFloat() / brakeSampled else 0f

        val tlSignal = when {
            hasRedTl && !hasGreenTl -> TrafficLightSignal.RED
            hasAmberTl && !hasRedTl -> TrafficLightSignal.AMBER
            hasGreenTl && !hasRedTl -> TrafficLightSignal.GREEN
            else -> TrafficLightSignal.NONE
        }

        return FrameLightingAndSignals(
            averageLuminance = avgLum,
            isDark = avgLum < DARKNESS_LUMINANCE_THRESHOLD,
            redBrakeLightsAhead = brakeRatio >= 0.008f || brakeBoxes.isNotEmpty(),
            trafficLightSignal = tlSignal,
            brakeLightBoxes = brakeBoxes,
        )
    }

    private fun clusterRedSpots(
        grid: Array<BooleanArray>,
        rows: Int,
        cols: Int,
    ): List<BrakeLightSpot> {
        val visited = Array(rows) { BooleanArray(cols) }
        val spots = mutableListOf<BrakeLightSpot>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!grid[r][c] || visited[r][c]) continue
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                var count = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.addLast(r to c)
                visited[r][c] = true
                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    count++
                    minR = minOf(minR, cr)
                    maxR = maxOf(maxR, cr)
                    minC = minOf(minC, cc)
                    maxC = maxOf(maxC, cc)
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && grid[nr][nc] && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.addLast(nr to nc)
                            }
                        }
                    }
                }
                if (count >= 2) {
                    spots.add(
                        BrakeLightSpot(
                            normLeft = (minC.toFloat() / cols).coerceIn(0f, 1f),
                            normTop = (minR.toFloat() / rows).coerceIn(0f, 1f),
                            normRight = ((maxC + 1).toFloat() / cols).coerceIn(0f, 1f),
                            normBottom = ((maxR + 1).toFloat() / rows).coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        }
        return spots.take(6)
    }

    private fun hasTrafficLightBulbCluster(
        colorGrid: Array<BooleanArray>,
        darkGrid: Array<BooleanArray>,
        rows: Int,
        cols: Int,
    ): Boolean {
        val visited = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!colorGrid[r][c] || visited[r][c]) continue
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                var count = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.addLast(r to c)
                visited[r][c] = true
                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    count++
                    minR = minOf(minR, cr)
                    maxR = maxOf(maxR, cr)
                    minC = minOf(minC, cc)
                    maxC = maxOf(maxC, cc)
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && colorGrid[nr][nc] && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.addLast(nr to nc)
                            }
                        }
                    }
                }
                // Compact traffic-light bulb cluster (2..14 cells) surrounded by dark traffic-light housing
                if (count in 2..14 && (maxR - minR) <= 4 && (maxC - minC) <= 4) {
                    var darkNeighbors = 0
                    for (nr in (minR - 2).coerceAtLeast(0)..(maxR + 2).coerceAtMost(rows - 1)) {
                        for (nc in (minC - 2).coerceAtLeast(0)..(maxC + 2).coerceAtMost(cols - 1)) {
                            if (darkGrid[nr][nc]) darkNeighbors++
                        }
                    }
                    if (darkNeighbors >= 2) return true
                }
            }
        }
        return false
    }

    const val DARKNESS_LUMINANCE_THRESHOLD = 48f
}
