package ke.co.kopilot.feature.location

import ke.co.kopilot.core.common.model.GpsFix
import ke.co.kopilot.core.common.model.GpsSignalState
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeedZone(
    val displayBanner: String?,
    val spokenAlert: String?,
) {
    NORMAL(null, null),
    ENCOURAGE_PACE(
        displayBanner = "60–80 km/h — Try to speed up a bit when safe",
        spokenAlert = "You are between 60 and 80 kilometres per hour. Try to speed up a bit when the road is clear.",
    ),
    OPTIMAL_CRUISE(
        displayBanner = "80–110 km/h — Optimal cruising speed",
        spokenAlert = "Optimal cruising speed.",
    ),
    REDUCE_SPEED(
        displayBanner = "Above 110 km/h — Please reduce your speed",
        spokenAlert = "Please reduce your speed.",
    ),
    DANGEROUS_DRIVING(
        displayBanner = "Above 120 km/h — Dangerous driving!",
        spokenAlert = "Warning: dangerous driving. Please reduce your speed immediately.",
    ),
}

data class SpeedZoneEvaluation(
    val zone: SpeedZone,
    val shouldNotify: Boolean,
)

@Singleton
class SpeedZoneMonitor @Inject constructor() {

    private var lastZone: SpeedZone = SpeedZone.NORMAL
    private var lastNotifiedAtMillis: Long = 0L

    fun evaluate(
        fix: GpsFix?,
        signalState: GpsSignalState,
        isDrivingActive: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): SpeedZoneEvaluation {
        if (!isDrivingActive || fix == null || signalState != GpsSignalState.GOOD) {
            lastZone = SpeedZone.NORMAL
            return SpeedZoneEvaluation(SpeedZone.NORMAL, shouldNotify = false)
        }

        val speedKmh = fix.speedKmh ?: return SpeedZoneEvaluation(SpeedZone.NORMAL, shouldNotify = false)
        val zone = when {
            speedKmh > 120f -> SpeedZone.DANGEROUS_DRIVING
            speedKmh > 110f -> SpeedZone.REDUCE_SPEED
            speedKmh >= 80f -> SpeedZone.OPTIMAL_CRUISE
            speedKmh >= 60f -> SpeedZone.ENCOURAGE_PACE
            else -> SpeedZone.NORMAL
        }

        if (zone == SpeedZone.NORMAL) {
            lastZone = SpeedZone.NORMAL
            return SpeedZoneEvaluation(SpeedZone.NORMAL, shouldNotify = false)
        }

        val zoneChanged = zone != lastZone
        val repeatCooldownExpired = (zone == SpeedZone.REDUCE_SPEED || zone == SpeedZone.DANGEROUS_DRIVING) &&
            (nowMillis - lastNotifiedAtMillis) >= HIGH_SPEED_REPEAT_COOLDOWN_MS

        val notify = (zoneChanged && (nowMillis - lastNotifiedAtMillis) >= ZONE_CHANGE_COOLDOWN_MS) ||
            repeatCooldownExpired

        if (notify) {
            lastZone = zone
            lastNotifiedAtMillis = nowMillis
        } else if (zoneChanged) {
            lastZone = zone
        }

        return SpeedZoneEvaluation(zone = zone, shouldNotify = notify)
    }

    fun reset() {
        lastZone = SpeedZone.NORMAL
        lastNotifiedAtMillis = 0L
    }

    companion object {
        const val ZONE_CHANGE_COOLDOWN_MS = 8_000L
        const val HIGH_SPEED_REPEAT_COOLDOWN_MS = 35_000L
    }
}
