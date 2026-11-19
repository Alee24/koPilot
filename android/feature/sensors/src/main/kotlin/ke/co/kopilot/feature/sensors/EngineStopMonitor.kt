package ke.co.kopilot.feature.sensors

enum class EngineStopClassification(val label: String, val isEmergencyCandidate: Boolean) {
    RUNNING("Engine running", false),
    NORMAL_SHUTDOWN("Normal engine shutdown", false),
    PARKED("Vehicle parked", false),
    TRAFFIC_STOP("Stopped at traffic / idle", false),
    ENGINE_STALL("Engine stall (non-emergency)", false),
    OBD_DISCONNECTED("OBD adapter disconnected", false),
    POSSIBLE_COLLISION_ENGINE_STOP("Possible collision-related engine stop", true),
}

data class EngineStopEvaluation(
    val classification: EngineStopClassification,
    val detail: String,
    val timestampMillis: Long,
)

/**
 * Distinguishes normal stops, traffic idles, engine stalls, and OBD link drops from
 * collision-related engine stops. Never treats OBD disconnect or an ordinary stall as an accident.
 */
class EngineStopMonitor {
    private var previousRpm: Int? = null
    private var wasObdConnected = false

    fun reset() {
        previousRpm = null
        wasObdConnected = false
    }

    fun evaluate(
        nowMillis: Long,
        obdConnected: Boolean,
        rpm: Int?,
        speedKmh: Float?,
        recentPeakImpactMps2: Float,
        impactThresholdMps2: Float,
        mountStatus: MountCalibrationStatus,
    ): EngineStopEvaluation {
        val speed = speedKmh ?: 0f

        if (!obdConnected) {
            val detail = if (wasObdConnected) {
                "OBD adapter disconnected; phone sensors remain active."
            } else {
                "OBD offline; monitoring via phone sensors and GPS."
            }
            wasObdConnected = false
            return EngineStopEvaluation(EngineStopClassification.OBD_DISCONNECTED, detail, nowMillis)
        }

        wasObdConnected = true
        val prev = previousRpm
        previousRpm = rpm

        if (rpm == null || rpm > 250) {
            return if (speed < 2.0f) {
                EngineStopEvaluation(
                    EngineStopClassification.TRAFFIC_STOP,
                    "Engine idling at ${rpm ?: "--"} RPM while stationary.",
                    nowMillis,
                )
            } else {
                EngineStopEvaluation(
                    EngineStopClassification.RUNNING,
                    "Engine running at ${rpm ?: "--"} RPM.",
                    nowMillis,
                )
            }
        }

        // RPM dropped to 0 (or <= 250) while OBD remains connected
        val droppedFromRunning = (prev ?: 0) >= 450
        val classification = when {
            droppedFromRunning &&
                recentPeakImpactMps2 >= impactThresholdMps2 &&
                mountStatus != MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED ->
                EngineStopClassification.POSSIBLE_COLLISION_ENGINE_STOP
            droppedFromRunning && speed >= 5.0f ->
                EngineStopClassification.ENGINE_STALL
            speed < 2.0f ->
                EngineStopClassification.PARKED
            else ->
                EngineStopClassification.NORMAL_SHUTDOWN
        }

        val detail = when (classification) {
            EngineStopClassification.POSSIBLE_COLLISION_ENGINE_STOP ->
                "Engine stopped after ${"%.1f".format(recentPeakImpactMps2)} m/s² impact."
            EngineStopClassification.ENGINE_STALL ->
                "Engine stalled while moving (${"%.0f".format(speed)} km/h); no collision impact."
            EngineStopClassification.PARKED ->
                "Vehicle stationary and engine off."
            else ->
                "Engine shutdown."
        }
        return EngineStopEvaluation(classification, detail, nowMillis)
    }
}
