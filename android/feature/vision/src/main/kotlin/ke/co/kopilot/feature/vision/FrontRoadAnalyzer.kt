package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

enum class TrafficLightSignal {
    NONE,
    RED,
    AMBER,
    GREEN,
    UNCERTAIN,
}

enum class TrafficLightOrientation {
    NONE,
    VERTICAL,
    HORIZONTAL,
}

data class TrafficLightObservation(
    val signal: TrafficLightSignal = TrafficLightSignal.NONE,
    val orientation: TrafficLightOrientation = TrafficLightOrientation.NONE,
    val confidence: Float = 0f,
    val normLeft: Float = 0f,
    val normTop: Float = 0f,
    val normRight: Float = 0f,
    val normBottom: Float = 0f,
)

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
    val trafficLightObservation: TrafficLightObservation = TrafficLightObservation(trafficLightSignal),
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

    fun analyzeLightingAndLights(
        bitmap: Bitmap,
        vehicleBoxes: List<TrackedRoadObject> = emptyList(),
    ): FrameLightingAndSignals {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) {
            return FrameLightingAndSignals(128f, isDark = false, redBrakeLightsAhead = false, TrafficLightSignal.NONE)
        }

        val gridCols = 48
        val gridRows = 36
        val stepX = (w.toFloat() / gridCols).coerceAtLeast(1f)
        val stepY = (h.toFloat() / gridRows).coerceAtLeast(1f)

        var lumSum = 0f
        var lumCount = 0
        var brakeRedHits = 0
        var brakeSampled = 0
        val redGrid = Array(gridRows) { BooleanArray(gridCols) }
        val tlRedGrid = Array(gridRows) { BooleanArray(gridCols) }
        val tlAmberGrid = Array(gridRows) { BooleanArray(gridCols) }
        val tlGreenGrid = Array(gridRows) { BooleanArray(gridCols) }
        val darkGrid = Array(gridRows) { BooleanArray(gridCols) }

        for (row in 0 until gridRows) {
            val y = ((row + 0.5f) * stepY).toInt().coerceIn(0, h - 1)
            val normY = y.toFloat() / h
            for (col in 0 until gridCols) {
                val x = ((col + 0.5f) * stepX).toInt().coerceIn(0, w - 1)
                val normX = x.toFloat() / w
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                lumSum += lum
                lumCount++

                val isBrightRed = r >= 175 && g <= 110 && b <= 110 && (r - g) >= 82 && (r - b) >= 82
                val isBrightAmber = r >= 200 && g in 125..215 && b <= 75 && (r - b) >= 120
                val isBrightGreen = g >= 180 && r <= 105 && (g - r) >= 80 && g >= b

                if (lum < 88f && maxOf(r, g, b) - minOf(r, g, b) < 42) {
                    darkGrid[row][col] = true
                }

                // Middle/lower road corridor: potential vehicle brake lights ahead
                if (normY in 0.34f..0.86f && normX in 0.14f..0.86f) {
                    if (isBrightRed) {
                        brakeRedHits++
                        redGrid[row][col] = true
                    }
                    brakeSampled++
                }

                // Upper/mid signal corridor (excluding lower road surface & vehicle tail-light zone):
                // Do not consider pixels inside a detected vehicle's lower body as traffic lights.
                if (normY in 0.03f..0.52f && normX in 0.06f..0.94f) {
                    val insideVehicleLowerBody = vehicleBoxes.any { v ->
                        normX in v.normalizedLeft..v.normalizedRight &&
                            normY in (v.normalizedTop + (v.normalizedBottom - v.normalizedTop) * 0.25f)..v.normalizedBottom
                    }
                    if (!insideVehicleLowerBody) {
                        if (isBrightRed) tlRedGrid[row][col] = true
                        if (isBrightAmber) tlAmberGrid[row][col] = true
                        if (isBrightGreen) tlGreenGrid[row][col] = true
                    }
                }
            }
        }

        val rawBrakeBoxes = clusterRedSpots(redGrid, gridRows, gridCols)
        // Filter out single high-altitude spots from brake boxes; brake lights are in the road lane or paired
        val brakeBoxes = rawBrakeBoxes.filter { spot ->
            val cy = (spot.normTop + spot.normBottom) * 0.5f
            cy >= 0.42f || rawBrakeBoxes.any { other ->
                other !== spot &&
                    abs(((other.normTop + other.normBottom) * 0.5f) - cy) <= 0.05f &&
                    abs(((other.normLeft + other.normRight) * 0.5f) - ((spot.normLeft + spot.normRight) * 0.5f)) in 0.05f..0.42f
            }
        }

        val tlObs = detectTrafficSignalWithHousing(
            tlRedGrid = tlRedGrid,
            tlAmberGrid = tlAmberGrid,
            tlGreenGrid = tlGreenGrid,
            darkGrid = darkGrid,
            rows = gridRows,
            cols = gridCols,
            brakeBoxes = brakeBoxes,
        )

        val avgLum = if (lumCount > 0) lumSum / lumCount else 128f
        val brakeRatio = if (brakeSampled > 0) brakeRedHits.toFloat() / brakeSampled else 0f

        return FrameLightingAndSignals(
            averageLuminance = avgLum,
            isDark = avgLum < DARKNESS_LUMINANCE_THRESHOLD,
            redBrakeLightsAhead = (brakeRatio >= 0.010f || brakeBoxes.isNotEmpty()) && tlObs.signal != TrafficLightSignal.RED,
            trafficLightSignal = tlObs.signal,
            brakeLightBoxes = brakeBoxes,
            trafficLightObservation = tlObs,
        )
    }

    private data class LampCandidate(
        val signal: TrafficLightSignal,
        val minR: Int,
        val maxR: Int,
        val minC: Int,
        val maxC: Int,
        val cellCount: Int,
        val orientation: TrafficLightOrientation,
        val confidence: Float,
    )

    /**
     * Validates:
     * 1. Compact circular/square illuminated lamp cluster (2..16 cells, aspect ~1:1).
     * 2. Dark traffic-signal housing surrounding the lamp in EITHER a vertical 3-aspect housing
     *    (dark cells extending below for RED, above & below for AMBER, above for GREEN) OR a
     *    horizontal 3-aspect housing (dark cells extending beside the lamp).
     * 3. Rejects horizontal symmetric pairs (vehicle brake lights / headlights).
     * 4. Reports UNCERTAIN when conflicting lamps or partial housing ambiguity occurs.
     */
    private fun detectTrafficSignalWithHousing(
        tlRedGrid: Array<BooleanArray>,
        tlAmberGrid: Array<BooleanArray>,
        tlGreenGrid: Array<BooleanArray>,
        darkGrid: Array<BooleanArray>,
        rows: Int,
        cols: Int,
        brakeBoxes: List<BrakeLightSpot>,
    ): TrafficLightObservation {
        val redLamps = findHousingValidatedLamps(tlRedGrid, darkGrid, rows, cols, TrafficLightSignal.RED, brakeBoxes)
        val amberLamps = findHousingValidatedLamps(tlAmberGrid, darkGrid, rows, cols, TrafficLightSignal.AMBER, brakeBoxes)
        val greenLamps = findHousingValidatedLamps(tlGreenGrid, darkGrid, rows, cols, TrafficLightSignal.GREEN, brakeBoxes)

        val allLamps = redLamps + amberLamps + greenLamps
        if (allLamps.isEmpty()) {
            return TrafficLightObservation(TrafficLightSignal.NONE)
        }

        val activeColors = listOfNotNull(
            redLamps.maxByOrNull { it.confidence },
            amberLamps.maxByOrNull { it.confidence },
            greenLamps.maxByOrNull { it.confidence },
        )

        // If Red and Green both appear valid simultaneously in the same housing vicinity, report UNCERTAIN rather than guessing
        if (redLamps.isNotEmpty() && greenLamps.isNotEmpty()) {
            val r = redLamps.first()
            return TrafficLightObservation(
                signal = TrafficLightSignal.UNCERTAIN,
                orientation = r.orientation,
                confidence = 0.45f,
                normLeft = r.minC.toFloat() / cols,
                normTop = r.minR.toFloat() / rows,
                normRight = (r.maxC + 1).toFloat() / cols,
                normBottom = (r.maxR + 1).toFloat() / rows,
            )
        }

        val best = activeColors.maxByOrNull { it.confidence } ?: return TrafficLightObservation(TrafficLightSignal.NONE)
        val finalSignal = if (best.confidence >= 0.62f) best.signal else TrafficLightSignal.UNCERTAIN

        return TrafficLightObservation(
            signal = finalSignal,
            orientation = best.orientation,
            confidence = best.confidence,
            normLeft = (best.minC.toFloat() / cols).coerceIn(0f, 1f),
            normTop = (best.minR.toFloat() / rows).coerceIn(0f, 1f),
            normRight = ((best.maxC + 1).toFloat() / cols).coerceIn(0f, 1f),
            normBottom = ((best.maxR + 1).toFloat() / rows).coerceIn(0f, 1f),
        )
    }

    private fun findHousingValidatedLamps(
        colorGrid: Array<BooleanArray>,
        darkGrid: Array<BooleanArray>,
        rows: Int,
        cols: Int,
        signalType: TrafficLightSignal,
        brakeBoxes: List<BrakeLightSpot>,
    ): List<LampCandidate> {
        val visited = Array(rows) { BooleanArray(cols) }
        val rawClusters = mutableListOf<IntArray>() // [minR, maxR, minC, maxC, count]

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
                val lampH = maxR - minR + 1
                val lampW = maxC - minC + 1
                // Lamp must be compact and roughly circular/square (not a wide strip or huge blob)
                if (count in 2..16 && lampH <= 5 && lampW <= 5 && abs(lampH - lampW) <= 2) {
                    rawClusters.add(intArrayOf(minR, maxR, minC, maxC, count))
                }
            }
        }

        val candidates = mutableListOf<LampCandidate>()
        for (cl in rawClusters) {
            val minR = cl[0]
            val maxR = cl[1]
            val minC = cl[2]
            val maxC = cl[3]
            val count = cl[4]
            val normCx = ((minC + maxC + 1) * 0.5f) / cols
            val normCy = ((minR + maxR + 1) * 0.5f) / rows

            // Reject if this cluster is part of a horizontal symmetric pair (e.g. twin vehicle tail/brake lights)
            val hasHorizontalTwin = rawClusters.any { other ->
                other !== cl &&
                    abs(other[0] - minR) <= 1 &&
                    abs(other[2] - minC) in 3..18
            }
            if (signalType == TrafficLightSignal.RED && hasHorizontalTwin && normCy >= 0.28f) {
                continue
            }
            // Also reject if overlapping a known lower-corridor brake light spot
            if (signalType == TrafficLightSignal.RED && brakeBoxes.any { b ->
                    normCx in (b.normLeft - 0.02f)..(b.normRight + 0.02f) &&
                        normCy in (b.normTop - 0.02f)..(b.normBottom + 0.02f) &&
                        normCy >= 0.36f
                }
            ) {
                continue
            }

            // Check dark housing cells above, below, left, and right of the illuminated lamp
            val darkAbove = countDarkCells(darkGrid, rows, cols, minR - 4, minR - 1, minC - 1, maxC + 1)
            val darkBelow = countDarkCells(darkGrid, rows, cols, maxR + 1, maxR + 4, minC - 1, maxC + 1)
            val darkLeft = countDarkCells(darkGrid, rows, cols, minR - 1, maxR + 1, minC - 4, minC - 1)
            val darkRight = countDarkCells(darkGrid, rows, cols, minR - 1, maxR + 1, maxC + 1, maxC + 4)
            val darkRing = countDarkCells(darkGrid, rows, cols, minR - 2, maxR + 2, minC - 2, maxC + 2)

            if (darkRing < 2) continue

            // Vertical Traffic Light Housing validation:
            // RED sits at top of housing (more dark housing below), AMBER sits in middle (dark above & below), GREEN sits at bottom (more dark housing above)
            val verticalMatch = when (signalType) {
                TrafficLightSignal.RED -> darkBelow >= 2 && (darkBelow + darkAbove) >= 3
                TrafficLightSignal.AMBER -> darkAbove >= 1 && darkBelow >= 1 && (darkAbove + darkBelow) >= 3
                TrafficLightSignal.GREEN -> darkAbove >= 2 && (darkAbove + darkBelow) >= 3
                else -> false
            }

            // Horizontal Traffic Light Housing validation:
            val horizontalMatch = when (signalType) {
                TrafficLightSignal.RED -> (darkRight >= 2 || darkLeft >= 2) && (darkLeft + darkRight) >= 3
                TrafficLightSignal.AMBER -> darkLeft >= 1 && darkRight >= 1 && (darkLeft + darkRight) >= 3
                TrafficLightSignal.GREEN -> (darkLeft >= 2 || darkRight >= 2) && (darkLeft + darkRight) >= 3
                else -> false
            }

            if (!verticalMatch && !horizontalMatch && darkRing < 4) continue

            val orientation = when {
                verticalMatch && (darkAbove + darkBelow) >= (darkLeft + darkRight) -> TrafficLightOrientation.VERTICAL
                horizontalMatch -> TrafficLightOrientation.HORIZONTAL
                else -> TrafficLightOrientation.VERTICAL
            }
            val housingStrength = maxOf(darkAbove + darkBelow, darkLeft + darkRight)
            val conf = when {
                (verticalMatch || horizontalMatch) && housingStrength >= 4 -> 0.86f
                (verticalMatch || horizontalMatch) && housingStrength >= 3 -> 0.72f
                darkRing >= 4 -> 0.52f // Ambiguous housing -> results in UNCERTAIN state
                else -> 0f
            }
            if (conf > 0f) {
                candidates.add(
                    LampCandidate(
                        signal = signalType,
                        minR = minR,
                        maxR = maxR,
                        minC = minC,
                        maxC = maxC,
                        cellCount = count,
                        orientation = orientation,
                        confidence = conf,
                    ),
                )
            }
        }
        return candidates
    }

    private fun countDarkCells(
        darkGrid: Array<BooleanArray>,
        rows: Int,
        cols: Int,
        r0: Int,
        r1: Int,
        c0: Int,
        c1: Int,
    ): Int {
        var hits = 0
        for (r in r0.coerceAtLeast(0)..r1.coerceAtMost(rows - 1)) {
            for (c in c0.coerceAtLeast(0)..c1.coerceAtMost(cols - 1)) {
                if (darkGrid[r][c]) hits++
            }
        }
        return hits
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
