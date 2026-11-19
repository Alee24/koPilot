package ke.co.kopilot.feature.sensors

import kotlin.math.roundToInt

data class CompassReading(
    val degrees: Int = 0,
    val cardinal: String = "N",
    val formatted: String = "N · 000°",
    val source: String = "IMU",
    val interferenceSuspected: Boolean = false,
)

/**
 * Estimates smooth vehicle heading: prefers GPS course when moving reliably (>= 6 km/h)
 * and falls back to smoothed orientation azimuth when stationary.
 */
class CompassHeadingEstimator {
    private var smoothedDeg: Float? = null

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
            var diff = targetDeg - current
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            normalize360(current + diff * 0.22f)
        }
        smoothedDeg = next

        val degInt = next.roundToInt() % 360
        val cardinal = toCardinal(degInt)
        val interference = !useGps && magneticFieldUt != null && (magneticFieldUt < 20f || magneticFieldUt > 75f)
        return CompassReading(
            degrees = degInt,
            cardinal = cardinal,
            formatted = "$cardinal · ${degInt.toString().padStart(3, '0')}°",
            source = if (useGps) "GPS" else "Sensor",
            interferenceSuspected = interference,
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
    }
}
