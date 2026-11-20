package ke.co.kopilot.feature.sensors

import kotlin.math.abs
import kotlin.math.roundToInt

data class CompassReading(
    val degrees: Int = 0,
    val cardinal: String = "N",
    val fullDirectionName: String = "North",
    val formatted: String = "North · 000°",
    val source: String = "IMU",
    val northReference: String = "Magnetic North",
    val isReliable: Boolean = true,
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
        val fullName = toFullDirectionName(cardinal)
        val reliable = useGps || (!interference && !sensorAzimuthDeg.isNaN())
        val northRef = if (useGps) "True North" else "Magnetic North"
        return CompassReading(
            degrees = degInt,
            cardinal = cardinal,
            fullDirectionName = fullName,
            formatted = if (reliable) "$fullName · ${degInt.toString().padStart(3, '0')}°" else "Heading unavailable",
            source = if (useGps) "GPS" else "Sensor",
            northReference = northRef,
            isReliable = reliable,
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
        private val FULL_NAMES = listOf(
            "North",
            "Northeast",
            "East",
            "Southeast",
            "South",
            "Southwest",
            "West",
            "Northwest",
        )

        fun toCardinal(degrees: Int): String {
            val norm = ((degrees % 360) + 360) % 360
            val idx = ((norm + 22.5) / 45.0).toInt() % 8
            return CARDINALS[idx]
        }

        fun toFullDirectionName(degrees: Int): String {
            val norm = ((degrees % 360) + 360) % 360
            val idx = ((norm + 22.5) / 45.0).toInt() % 8
            return FULL_NAMES[idx]
        }

        fun toFullDirectionName(cardinal: String): String = when (cardinal.trim().uppercase()) {
            "N", "NORTH" -> "North"
            "NE", "NORTHEAST" -> "Northeast"
            "E", "EAST" -> "East"
            "SE", "SOUTHEAST" -> "Southeast"
            "S", "SOUTH" -> "South"
            "SW", "SOUTHWEST" -> "Southwest"
            "W", "WEST" -> "West"
            "NW", "NORTHWEST" -> "Northwest"
            else -> "Heading unavailable"
        }

        fun formatSpokenHeading(reading: CompassReading?): String {
            if (reading == null || !reading.isReliable || reading.interferenceSuspected) {
                return "Heading unavailable"
            }
            return toFullDirectionName(reading.degrees)
        }

        fun formatDisplayHeading(reading: CompassReading?): String {
            if (reading == null || !reading.isReliable || reading.interferenceSuspected) {
                return "Heading unavailable"
            }
            val dir = toFullDirectionName(reading.degrees)
            return "$dir · ${reading.degrees.toString().padStart(3, '0')}°"
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
