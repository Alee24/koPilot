package ke.co.kopilot.feature.soundstudio

/**
 * Pure, unit-testable OBD-II RPM & throttle processor.
 * - Rejects stale readings (> staleTimeoutMs)
 * - Smooths noisy adapter updates and interpolates between samples
 * - Fades RPM & gain safely to 0 when OBD disconnects or data goes stale
 * - Derives conservative throttle estimate from engine load / RPM delta if PID 0111 is absent
 */
class ObdRpmSmoother(
    private val staleTimeoutMs: Long = 8_000L,
) {
    data class ProcessedTelemetry(
        val smoothedRpm: Float,
        val throttleFraction: Float,
        val rpmDeltaPerSec: Float,
        val isStale: Boolean,
        val safeOutputScale: Float,
    )

    private var currentRpm = 0f
    private var currentThrottle = 0.25f
    private var lastValidSampleMs = 0L
    private var lastRawRpm = 0f
    private var lastRawSampleMs = 0L
    private var lastRpmDeltaPerSec = 0f
    private var safeScale = 0f

    fun reset() {
        currentRpm = 0f
        currentThrottle = 0.25f
        lastValidSampleMs = 0L
        lastRawRpm = 0f
        lastRawSampleMs = 0L
        lastRpmDeltaPerSec = 0f
        safeScale = 0f
    }

    fun update(
        obdConnected: Boolean,
        rawRpm: Int?,
        rawThrottlePct: Float?,
        rawEngineLoadPct: Float?,
        sampleTimestampMs: Long,
        nowMs: Long,
        responsiveness: Float,
        dtSec: Float,
    ): ProcessedTelemetry {
        val isStale = !obdConnected ||
            rawRpm == null ||
            sampleTimestampMs <= 0L ||
            (nowMs - sampleTimestampMs) > staleTimeoutMs

        if (isStale) {
            // Safe fade-out: never keep accelerating or holding stale RPM indefinitely
            val fadeRate = (dtSec * 2.5f).coerceIn(0.02f, 1f)
            safeScale = (safeScale - fadeRate).coerceAtLeast(0f)
            currentRpm = (currentRpm * (1f - fadeRate * 0.5f)).coerceAtLeast(0f)
            lastRpmDeltaPerSec = 0f
            return ProcessedTelemetry(
                smoothedRpm = currentRpm,
                throttleFraction = currentThrottle,
                rpmDeltaPerSec = 0f,
                isStale = true,
                safeOutputScale = safeScale,
            )
        }

        val targetRpm = rawRpm!!.toFloat().coerceIn(0f, 10_000f)
        if (sampleTimestampMs > lastRawSampleMs && lastRawSampleMs > 0L) {
            val sampleDt = ((sampleTimestampMs - lastRawSampleMs) / 1000f).coerceAtLeast(0.05f)
            lastRpmDeltaPerSec = (targetRpm - lastRawRpm) / sampleDt
        }
        if (sampleTimestampMs > lastRawSampleMs) {
            lastRawRpm = targetRpm
            lastRawSampleMs = sampleTimestampMs
        }
        lastValidSampleMs = nowMs

        // Smooth interpolation toward target RPM based on responsiveness (0.2..1.0)
        val alpha = (responsiveness.coerceIn(0.15f, 1.0f) * dtSec * 12f).coerceIn(0.05f, 0.85f)
        if (currentRpm <= 1f && targetRpm > 0f) {
            currentRpm = targetRpm
        } else {
            currentRpm += (targetRpm - currentRpm) * alpha
        }

        // Throttle fallback when PID 0111 is unsupported or null
        val targetThrottle = when {
            rawThrottlePct != null -> (rawThrottlePct / 100f).coerceIn(0f, 1f)
            rawEngineLoadPct != null -> (rawEngineLoadPct / 100f).coerceIn(0.1f, 1f)
            else -> {
                val accelBoost = (lastRpmDeltaPerSec / 2000f).coerceIn(-0.2f, 0.5f)
                (0.28f + accelBoost).coerceIn(0.1f, 0.9f)
            }
        }
        currentThrottle += (targetThrottle - currentThrottle) * alpha.coerceAtMost(0.5f)

        safeScale = (safeScale + dtSec * 3.0f).coerceAtMost(1f)
        return ProcessedTelemetry(
            smoothedRpm = currentRpm,
            throttleFraction = currentThrottle,
            rpmDeltaPerSec = lastRpmDeltaPerSec,
            isStale = false,
            safeOutputScale = safeScale,
        )
    }
}
