package ke.co.kopilot.feature.sensors

import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class SensorFrame(
    val timestampMillis: Long,
    val forwardAccelMps2: Float,
    val lateralAccelMps2: Float,
    val verticalAccelMps2: Float,
    val linearMagMps2: Float,
    val angularVelDegPerSec: Float,
    val pitchDeltaDeg: Float,
    val rollDeltaDeg: Float,
    val speedKmh: Float?,
    val gpsAccuracyMeters: Float?,
    val obdRpm: Int?,
    val monotonicMillis: Long = timestampMillis,
    val gpsSpeedKmh: Float? = speedKmh,
    val gpsCourseDeg: Float? = null,
    val obdSpeedKmh: Float? = null,
    val obdConnected: Boolean = false,
    val obdEcuLost: Boolean = false,
    val watchHeartRateBpm: Int? = null,
    val watchBaselineBpm: Float? = null,
    val watchBaselineSampleCount: Int = 0,
    val watchReadingAgeMs: Long? = null,
    val watchSensorContact: Boolean? = null,
) {
    fun bestIndependentSpeedKmh(): Float? = gpsSpeedKmh ?: obdSpeedKmh ?: speedKmh
}

data class AccidentEngineOutput(
    val assessment: AccidentAssessment,
    val swerveClass: SwerveClass,
    val swerveAnalysis: SwerveAnalysisResult,
    val postImpactMonitoringActive: Boolean,
    val postImpactElapsedSec: Long,
    val bufferSize: Int,
    val evidenceSnapshot: IncidentEvidenceSnapshot? = null,
)

/**
 * Deterministic multi-sensor accident & swerving detection engine.
 *
 * Uses monotonic timestamps, low-pass noise filtering, 30-second synchronized rolling history,
 * a 4-group independent confirmation engine, an explicit 8-state machine, and a dedicated
 * swerving detector + rolling evidence buffer.
 */
class AccidentDetectionEngine(
    private val confidenceEvaluator: AccidentConfidenceEvaluator = AccidentConfidenceEvaluator(),
    private val swervingDetector: SwervingDetector = SwervingDetector(),
    private val evidenceRecorder: IncidentEvidenceRecorder = IncidentEvidenceRecorder(),
    private val roughRoadClassifier: RoughRoadClassifier = RoughRoadClassifier(),
) {
    private val buffer = ArrayDeque<SensorFrame>()
    private var currentState: AccidentState = AccidentState.NORMAL
    private var activeIncidentId: String? = null
    private var lastTriggerMonotonicMs: Long = 0L
    private var lastCanceledMonotonicMs: Long = 0L
    private var lastCanceledIncidentId: String? = null
    private var suspiciousStartMonotonicMs: Long = 0L
    private var significantImpactMonotonicMs: Long = 0L
    private var preImpactGpsSpeedKmh: Float? = null
    private var preImpactObdSpeedKmh: Float? = null
    private var preImpactObdRpm: Int? = null
    private var lastSwerveEvidenceMonotonicMs: Long = 0L

    // Exponential low-pass filter state for noise rejection while preserving genuine impacts
    private var filteredLinearMag: Float = 0f
    private var filteredAngularVel: Float = 0f
    private var prevFrameMonotonicMs: Long = 0L
    private var prevLinearMag: Float = 0f

    fun getCurrentState(): AccidentState = currentState

    fun reset() {
        buffer.clear()
        currentState = AccidentState.NORMAL
        activeIncidentId = null
        lastTriggerMonotonicMs = 0L
        lastCanceledMonotonicMs = 0L
        lastCanceledIncidentId = null
        suspiciousStartMonotonicMs = 0L
        significantImpactMonotonicMs = 0L
        preImpactGpsSpeedKmh = null
        preImpactObdSpeedKmh = null
        preImpactObdRpm = null
        lastSwerveEvidenceMonotonicMs = 0L
        filteredLinearMag = 0f
        filteredAngularVel = 0f
        prevFrameMonotonicMs = 0L
        prevLinearMag = 0f
    }

    /**
     * Driver tapped "I'm Safe — Cancel". Transitions deterministically to CANCELED
     * and suppresses immediate re-triggering from the same event.
     */
    fun markCanceled(incidentId: String, nowMonotonicMs: Long) {
        currentState = AccidentState.CANCELED
        lastCanceledIncidentId = incidentId
        lastCanceledMonotonicMs = nowMonotonicMs
        activeIncidentId = null
        suspiciousStartMonotonicMs = 0L
        significantImpactMonotonicMs = 0L
    }

    /**
     * Countdown expired or driver tapped "Send SOS Now".
     */
    fun markEmergencyNotified(incidentId: String) {
        currentState = AccidentState.EMERGENCY_NOTIFICATION
        activeIncidentId = incidentId
    }

    /**
     * Returns state machine cleanly to NORMAL after emergency flow is acknowledged.
     */
    fun acknowledgeEmergencyCompleted() {
        currentState = AccidentState.NORMAL
        activeIncidentId = null
        suspiciousStartMonotonicMs = 0L
    }

    fun processFrame(
        frame: SensorFrame,
        mountStatus: MountCalibrationStatus,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        obdConnected: Boolean,
        sensitivity: AccidentSensitivityProfile = AccidentSensitivityProfile.STANDARD,
        impactEnabled: Boolean = true,
        rolloverEnabled: Boolean = true,
        swerveEnabled: Boolean = true,
        thresholdConfig: AccidentThresholdConfig? = null,
        videoBufferAvailable: Boolean = false,
    ): AccidentEngineOutput {
        val cfg = (thresholdConfig ?: AccidentThresholdConfig.fromSensitivity(sensitivity)).sanitized()
        val mono = frame.monotonicMillis

        // Low-pass noise filter (alpha = 0.65 preserves multi-sample impacts while damping 1-sample micro-jitter)
        val alpha = 0.65f
        filteredLinearMag = if (prevFrameMonotonicMs == 0L) {
            frame.linearMagMps2
        } else {
            alpha * frame.linearMagMps2 + (1f - alpha) * filteredLinearMag
        }
        filteredAngularVel = if (prevFrameMonotonicMs == 0L) {
            frame.angularVelDegPerSec
        } else {
            alpha * frame.angularVelDegPerSec + (1f - alpha) * filteredAngularVel
        }

        val dtMs = if (prevFrameMonotonicMs > 0L) (mono - prevFrameMonotonicMs).coerceAtLeast(1L) else 0L
        val jerkMps3 = if (dtMs > 0L) abs(frame.linearMagMps2 - prevLinearMag) / (dtMs / 1000f) else 0f
        prevFrameMonotonicMs = mono
        prevLinearMag = frame.linearMagMps2

        val effectiveFrame = frame.copy(obdConnected = frame.obdConnected || obdConnected)
        buffer.addLast(effectiveFrame)
        evidenceRecorder.recordFrame(effectiveFrame, cfg.rollingBufferPreSec)

        val cutoff = mono - BUFFER_WINDOW_MS
        while (buffer.isNotEmpty() && buffer.first.monotonicMillis < cutoff) {
            buffer.removeFirst()
        }

        val windowStart = if (suspiciousStartMonotonicMs > 0L) {
            minOf(mono - 5_000L, suspiciousStartMonotonicMs - 200L)
        } else {
            mono - 5_000L
        }
        val recent5s = buffer.filter { it.monotonicMillis >= windowStart }
        val recentImpactWindow = buffer.filter { it.monotonicMillis >= mono - 1_500L }
        val preWindow = buffer.filter {
            it.monotonicMillis in (mono - 6_000L)..(mono - 1_200L)
        }

        // Compute sensor quality & sampling gaps over recent window
        var maxGapMs = 0L
        for (i in 1 until recent5s.size) {
            val gap = recent5s[i].monotonicMillis - recent5s[i - 1].monotonicMillis
            if (gap > maxGapMs) maxGapMs = gap
        }
        val spanSec = if (recent5s.size >= 2) {
            ((recent5s.last().monotonicMillis - recent5s.first().monotonicMillis).coerceAtLeast(1L)) / 1000f
        } else {
            0f
        }
        val samplingHz = if (spanSec > 0f) (recent5s.size - 1) / spanSec else 0f

        // Compute sustained impact duration and sample counts over the 5s event window
        // (so post-impact stillness verification still sees the sustained multi-sample impact)
        val impactFrames = recent5s.filter { it.linearMagMps2 >= cfg.strongImpactMps2 * 0.72f }
        val severeFrames = recent5s.filter { it.linearMagMps2 >= cfg.severeImpactMps2 * 0.85f }
        val motionEventDurationMs = if (impactFrames.size >= 2) {
            (impactFrames.last().monotonicMillis - impactFrames.first().monotonicMillis).coerceAtLeast(0L)
        } else {
            0L
        }

        val peakMag = recent5s.maxOfOrNull { it.linearMagMps2 } ?: frame.linearMagMps2
        val peakHoriz = recent5s.maxOfOrNull {
            sqrt(it.forwardAccelMps2 * it.forwardAccelMps2 + it.lateralAccelMps2 * it.lateralAccelMps2)
        } ?: 0f
        val peakVert = recent5s.maxOfOrNull { abs(it.verticalAccelMps2) } ?: abs(frame.verticalAccelMps2)
        val peakGyro = recent5s.maxOfOrNull { abs(it.angularVelDegPerSec) } ?: abs(frame.angularVelDegPerSec)
        val maxRollDelta = recent5s.maxOfOrNull { abs(it.rollDeltaDeg) } ?: abs(frame.rollDeltaDeg)
        val maxPitchDelta = recent5s.maxOfOrNull { abs(it.pitchDeltaDeg) } ?: abs(frame.pitchDeltaDeg)

        // Sustained abnormal orientation duration (for rollover validation)
        val abnormalTiltFrames = recent5s.filter {
            maxOf(abs(it.rollDeltaDeg), abs(it.pitchDeltaDeg)) >= cfg.rolloverAngleDeg
        }
        val sustainedAbnormalOrientationMs = if (abnormalTiltFrames.size >= 2) {
            (abnormalTiltFrames.last().monotonicMillis - abnormalTiltFrames.first().monotonicMillis).coerceAtLeast(0L)
        } else {
            0L
        }

        // Sustained longitudinal deceleration (for harsh braking classification)
        val brakingFrames = recentImpactWindow.filter { it.forwardAccelMps2 <= cfg.harshBrakingMps2 }
        val sustainedLongDecel = if (brakingFrames.size >= 2) {
            brakingFrames.minOf { it.forwardAccelMps2 }
        } else {
            0f
        }

        // Pre-event baselines (GPS speed, OBD speed, OBD RPM strictly separated)
        val preGpsSpeed = preWindow.mapNotNull { it.gpsSpeedKmh }.maxOrNull() ?: preImpactGpsSpeedKmh
        val preObdSpeed = preWindow.mapNotNull { it.obdSpeedKmh }.maxOrNull() ?: preImpactObdSpeedKmh
        val preObdRpm = preWindow.mapNotNull { it.obdRpm }.maxOrNull() ?: preImpactObdRpm

        val suspiciousMotion = (impactFrames.size >= 2 && peakMag >= cfg.strongImpactMps2) ||
            (maxOf(maxRollDelta, maxPitchDelta) >= cfg.rolloverAngleDeg && peakGyro >= 80f)

        if (suspiciousMotion && significantImpactMonotonicMs == 0L) {
            significantImpactMonotonicMs = mono
            suspiciousStartMonotonicMs = mono
            preImpactGpsSpeedKmh = preGpsSpeed
            preImpactObdSpeedKmh = preObdSpeed
            preImpactObdRpm = preObdRpm
            if (currentState == AccidentState.NORMAL) {
                currentState = AccidentState.SUSPICIOUS_EVENT
            }
        }

        val postImpactStationarySec = if (significantImpactMonotonicMs > 0L) {
            val stationaryFrames = buffer.count {
                it.monotonicMillis >= significantImpactMonotonicMs && (it.bestIndependentSpeedKmh() ?: 99f) < 3.0f
            }
            if ((effectiveFrame.bestIndependentSpeedKmh() ?: 99f) < 3.0f && stationaryFrames >= 3) {
                (mono - significantImpactMonotonicMs) / 1000f
            } else {
                0f
            }
        } else {
            0f
        }

        // Post-impact stillness (low linear acceleration < 0.35g after severe shock — used for SEVERE_EVENT_FALLBACK)
        val postImpactStillnessSec = if (significantImpactMonotonicMs > 0L) {
            val stillFrames = buffer.filter {
                it.monotonicMillis > significantImpactMonotonicMs &&
                    it.linearMagMps2 < cfg.minorMovementMps2 * 0.8f &&
                    abs(it.angularVelDegPerSec) < 20f
            }
            if (stillFrames.size >= 2 && effectiveFrame.linearMagMps2 < cfg.minorMovementMps2 * 0.8f) {
                (stillFrames.last().monotonicMillis - stillFrames.first().monotonicMillis) / 1000f
            } else {
                0f
            }
        } else {
            0f
        }

        val obdEcuLostDuringEvent = recent5s.any { it.obdEcuLost }

        // Dedicated multi-feature swerving analysis (never triggers emergency SMS)
        val swerveAnalysis = if (swerveEnabled) {
            swervingDetector.analyze(recent5s, cfg)
        } else {
            SwerveAnalysisResult(
                category = SwerveBehaviorCategory.NONE,
                legacySwerveClass = SwerveClass.NORMAL_TURNING,
                peakLateralMps2 = 0f,
                peakYawDegPerSec = 0f,
                cumulativeCourseChangeDeg = 0f,
                reversalCount = 0,
                reason = "Swerving detection disabled",
                shouldPreserveEvidence = false,
            )
        }

        val qualitySnapshot = SensorQualitySnapshot(
            samplingRateHz = samplingHz,
            maxSamplingGapMs = maxGapMs,
            qualityLabel = when {
                !accelAvailable -> "Degraded (No Accelerometer)"
                maxGapMs > 1_500L -> "Gaps Detected (${maxGapMs}ms)"
                gyroAvailable -> "Good (${"%.1f".format(samplingHz)} Hz)"
                else -> "Moderate (No Gyro)"
            },
            mountConfidenceLabel = mountStatus.label,
            jerkMps3 = jerkMps3,
            motionEventDurationMs = motionEventDurationMs,
        )

        val verificationElapsedMs = if (suspiciousStartMonotonicMs > 0L) {
            (mono - suspiciousStartMonotonicMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val inVerificationWindow = suspiciousStartMonotonicMs > 0L &&
            verificationElapsedMs < VERIFICATION_WINDOW_MS

        // Multi-sample post-impact movement check over the last 2s (avoids GPS jitter / single reading)
        val recent2s = buffer.filter { it.monotonicMillis >= mono - 2_000L }
        val recentSpeeds = recent2s.mapNotNull { it.bestIndependentSpeedKmh() }.sorted()
        val medianPostSpeed = if (recentSpeeds.isNotEmpty()) recentSpeeds[recentSpeeds.size / 2] else 0f
        val secondaryHorizCollisionInLast2s = recent2s.any {
            sqrt(it.forwardAccelMps2 * it.forwardAccelMps2 + it.lateralAccelMps2 * it.lateralAccelMps2) >= cfg.strongImpactMps2
        }
        val vehicleContinuedMovingNormally = recentSpeeds.size >= 2 &&
            medianPostSpeed >= 12.0f &&
            maxOf(maxRollDelta, maxPitchDelta) < cfg.rolloverAngleDeg &&
            (!secondaryHorizCollisionInLast2s || verificationElapsedMs >= VERIFICATION_WINDOW_MS)

        val roadResult = roughRoadClassifier.classify(
            frames = recent5s,
            cfg = cfg,
            orientationDeltaDeg = maxOf(maxRollDelta, maxPitchDelta),
            abruptSpeedDropKmh = maxOf(
                (preGpsSpeed ?: 0f) - (frame.gpsSpeedKmh ?: preGpsSpeed ?: 0f),
                (preObdSpeed ?: 0f) - (frame.obdSpeedKmh ?: preObdSpeed ?: 0f),
            ).coerceAtLeast(0f),
            vehicleStillMoving = medianPostSpeed >= 12.0f,
        )

        val candidateIncidentId = activeIncidentId ?: "inc_${significantImpactMonotonicMs.takeIf { it > 0L } ?: mono}"

        var assessment = confidenceEvaluator.evaluate(
            timestampMillis = frame.timestampMillis,
            peakLinearAccelMps2 = peakMag,
            horizontalImpulseMps2 = peakHoriz,
            verticalImpulseMps2 = peakVert,
            sustainedLongitudinalDecelMps2 = sustainedLongDecel,
            sustainedImpactSamples = impactFrames.size,
            sustainedSevereSamples = severeFrames.size,
            motionEventDurationMs = motionEventDurationMs,
            preEventGpsSpeedKmh = preImpactGpsSpeedKmh ?: preGpsSpeed,
            postEventGpsSpeedKmh = frame.gpsSpeedKmh,
            preEventObdSpeedKmh = preImpactObdSpeedKmh ?: preObdSpeed,
            postEventObdSpeedKmh = frame.obdSpeedKmh,
            preEventObdRpm = preImpactObdRpm ?: preObdRpm,
            postEventObdRpm = frame.obdRpm,
            obdEcuLostDuringEvent = obdEcuLostDuringEvent,
            watchHeartRateBpm = frame.watchHeartRateBpm,
            watchBaselineBpm = frame.watchBaselineBpm,
            watchBaselineSampleCount = frame.watchBaselineSampleCount,
            watchReadingAgeMs = frame.watchReadingAgeMs,
            watchSensorContact = frame.watchSensorContact,
            rollChangeDeg = maxRollDelta,
            pitchChangeDeg = maxPitchDelta,
            sustainedAbnormalOrientationMs = sustainedAbnormalOrientationMs,
            peakAngularVelDegPerSec = peakGyro,
            postImpactStationarySec = postImpactStationarySec,
            postImpactStillnessSec = postImpactStillnessSec,
            mountCalibrationStatus = mountStatus,
            gpsAccuracyMeters = frame.gpsAccuracyMeters,
            accelAvailable = accelAvailable,
            gyroAvailable = gyroAvailable,
            obdConnected = effectiveFrame.obdConnected,
            config = cfg,
            impactEnabled = impactEnabled,
            rolloverEnabled = rolloverEnabled,
            inVerificationWindow = inVerificationWindow,
            verificationElapsedMs = verificationElapsedMs,
            vehicleContinuedMovingNormally = vehicleContinuedMovingNormally,
            roadResult = roadResult,
            incidentId = candidateIncidentId,
            sensorQuality = qualitySnapshot,
        ).copy(
            swerveClass = swerveAnalysis.legacySwerveClass,
            swerveCategory = swerveAnalysis.category,
        )

        // Enforce cancellation suppression and duplicate incident cooldown
        val withinCancelCooldown = lastCanceledMonotonicMs > 0L &&
            (mono - lastCanceledMonotonicMs) < CANCEL_SUPPRESSION_MS
        val withinTriggerCooldown = lastTriggerMonotonicMs > 0L &&
            (mono - lastTriggerMonotonicMs) < COOLDOWN_MS

        var capturedEvidence: IncidentEvidenceSnapshot? = null

        if (withinCancelCooldown) {
            assessment = assessment.copy(
                eventClass = if (assessment.eventClass.triggersCountdown) AccidentEventClass.SIGNIFICANT_IMPACT else assessment.eventClass,
                state = AccidentState.CANCELED,
            )
            currentState = AccidentState.CANCELED
        } else if (assessment.eventClass.triggersCountdown) {
            if (withinTriggerCooldown) {
                assessment = assessment.copy(
                    eventClass = AccidentEventClass.SIGNIFICANT_IMPACT,
                    state = AccidentState.DRIVING_EVENT_ONLY,
                )
            } else {
                val incidentId = UUID.randomUUID().toString().take(12)
                activeIncidentId = incidentId
                lastTriggerMonotonicMs = mono
                currentState = assessment.state
                assessment = assessment.copy(incidentId = incidentId)
                capturedEvidence = evidenceRecorder.startEventCapture(
                    incidentId = incidentId,
                    eventCategory = assessment.eventClass.label,
                    nowMillis = frame.timestampMillis,
                    nowMonotonicMs = mono,
                    preBufferSec = cfg.rollingBufferPreSec,
                    postBufferSec = cfg.rollingBufferPostSec,
                    videoBufferAvailable = videoBufferAvailable,
                )
            }
        } else {
            if (currentState != AccidentState.EMERGENCY_NOTIFICATION &&
                !(currentState == AccidentState.CANCELED && withinCancelCooldown)
            ) {
                currentState = assessment.state
            }
            // Preserve rolling evidence for erratic swerving / abrupt evasive maneuver (never triggers SMS)
            if (swerveAnalysis.shouldPreserveEvidence &&
                (lastSwerveEvidenceMonotonicMs == 0L || (mono - lastSwerveEvidenceMonotonicMs) >= SWERVE_EVIDENCE_COOLDOWN_MS)
            ) {
                lastSwerveEvidenceMonotonicMs = mono
                capturedEvidence = evidenceRecorder.startEventCapture(
                    incidentId = "swerve_${UUID.randomUUID().toString().take(8)}",
                    eventCategory = swerveAnalysis.category.label,
                    nowMillis = frame.timestampMillis,
                    nowMonotonicMs = mono,
                    preBufferSec = cfg.rollingBufferPreSec,
                    postBufferSec = cfg.rollingBufferPostSec,
                    videoBufferAvailable = videoBufferAvailable,
                )
            }
        }

        if (assessment.state == AccidentState.DISMISSED_ROAD_EVENT) {
            significantImpactMonotonicMs = 0L
            suspiciousStartMonotonicMs = 0L
            preImpactGpsSpeedKmh = null
            preImpactObdSpeedKmh = null
            preImpactObdRpm = null
        }

        val postActive = significantImpactMonotonicMs > 0L &&
            (mono - significantImpactMonotonicMs) <= POST_IMPACT_WINDOW_MS
        if (!postActive && (effectiveFrame.bestIndependentSpeedKmh() ?: 0f) > 12f) {
            significantImpactMonotonicMs = 0L
            suspiciousStartMonotonicMs = 0L
            preImpactGpsSpeedKmh = null
            preImpactObdSpeedKmh = null
            preImpactObdRpm = null
        }

        return AccidentEngineOutput(
            assessment = assessment,
            swerveClass = swerveAnalysis.legacySwerveClass,
            swerveAnalysis = swerveAnalysis,
            postImpactMonitoringActive = postActive,
            postImpactElapsedSec = if (postActive) (mono - significantImpactMonotonicMs) / 1000L else 0L,
            bufferSize = buffer.size,
            evidenceSnapshot = capturedEvidence,
        )
    }

    companion object {
        const val BUFFER_WINDOW_MS = 30_000L
        const val VERIFICATION_WINDOW_MS = 5_000L
        const val COOLDOWN_MS = 30_000L
        const val CANCEL_SUPPRESSION_MS = 45_000L
        const val SWERVE_EVIDENCE_COOLDOWN_MS = 20_000L
        const val POST_IMPACT_WINDOW_MS = 180_000L
    }
}
