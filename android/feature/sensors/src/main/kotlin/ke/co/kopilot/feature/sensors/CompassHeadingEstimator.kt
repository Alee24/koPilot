package ke.co.kopilot.feature.sensors

import kotlin.math.abs
import kotlin.math.roundToInt

data class CompassReading(
    val degrees: Int = 0,
    val cardinal: String = "N",
    val formatted: String = "N · 000°",
    val source: String = "IMU",
    val interferenceSuspected: Boolean = false,
    val headingDeltaDegrees: Float = 0f,
    val previousCardinal: String = "N",
)

/**
 * Estimates smooth vehicle heading: prefers GPS course when moving reliably (>= 6 km/h)
 * and falls back to smoothed orientation azimuth when stationary.
 * Also tracks heading delta and cardinal transitions to inform compass-based turning.
 */
class CompassHeadingEstimator {
    private var smoothedDeg: Float? = null
    private var referenceHeadingDeg: Float? = null
    private var referenceCardinal: String = "N"

    fun update(
        gpsSpeedKmh: Float?,
        gpsBearingDeg: Float?,
        sensorAzimuthDeg: Float,
        magneticFieldUt: Float? = null,
    ): CompassReading {
        val useGps = (gpsSpeedKmh ?: 0f) >= 6.0f && gpsBearingDeg != null && gpsBearingDeg >= 0f
        val targetDeg = normalize360(if (useGps) gpsBearingDeg!! else sensorAzimuthDeg)

        val current = smoothedDeg
        val next = if (current == null) {
            targetDeg
        } else {
            val diff = signedAngleDelta(current, targetDeg)
            normalize360(current + diff * 0.22f)
        }
        smoothedDeg = next

        val degInt = next.roundToInt() % 360
        val cardinal = toCardinal(degInt)

        val ref = referenceHeadingDeg
        val deltaFromRef = if (ref == null) {
            referenceHeadingDeg = next
            referenceCardinal = cardinal
            0f
        } else {
            val d = signedAngleDelta(ref, next)
            // Gently drift reference heading toward current heading so steady curves/straights stay calibrated
            referenceHeadingDeg = normalize360(ref + d * 0.08f)
            if (abs(d) < 8f) {
                referenceCardinal = cardinal
            }
            d
        }
        val prevCardinal = referenceCardinal

        val interference = !useGps && magneticFieldUt != null && (magneticFieldUt < 20f || magneticFieldUt > 75f)
        return CompassReading(
            degrees = degInt,
            cardinal = cardinal,
            formatted = "$cardinal · ${degInt.toString().padStart(3, '0')}°",
            source = if (useGps) "GPS" else "Sensor",
            interferenceSuspected = interference,
            headingDeltaDegrees = deltaFromRef,
            previousCardinal = prevCardinal,
        )
    }

    private fun normalize360(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    companion object {
        private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        fun toCardinal(degrees: Int): String {
            val norm = ((degrees % 360) + 360) % 360
            val idx = ((norm + 22.5) / 45.0).toInt() % 8
            return CARDINALS[idx]
        }

        /**
         * Returns shortest signed angle difference (toDeg - fromDeg) in [-180f, +180f].
         * Positive = clockwise (turning right, e.g. N -> NE or NW -> N).
         * Negative = counter-clockwise (turning left, e.g. NE -> N or N -> NW).
         */
        fun signedAngleDelta(fromDeg: Float, toDeg: Float): Float {
            var diff = (toDeg - fromDeg) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            return diff
        }
    }
}
