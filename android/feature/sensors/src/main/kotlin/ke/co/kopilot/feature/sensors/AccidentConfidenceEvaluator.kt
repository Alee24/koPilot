package ke.co.kopilot.feature.sensors

import kotlin.math.abs

enum class AccidentEventClass(val label: String, val triggersCountdown: Boolean) {
    NORMAL_DRIVING("Normal driving", false),
    MINOR_ROAD_IMPACT("Minor road impact", false),
    SIGNIFICANT_IMPACT("Significant impact", false),
    SUSPECTED_COLLISION("Suspected collision", true),
    SUSPECTED_ROLLOVER("Suspected rollover", true),
    HIGH_CONFIDENCE_POSSIBLE_ACCIDENT("High-confidence possible accident", true),
}

enum class SwerveClass(val label: String) {
    NORMAL_TURNING("Normal turning"),
    SHARP_TURNING("Sharp turning"),
    LANE_CHANGE("Sudden lane-change movement"),
    POSSIBLE_SWERVING("Possible swerving"),
    LOSS_OF_CONTROL("Possible loss-of-control pattern"),
}

enum class AccidentSensitivityProfile(
    val label: String,
    val impactThresholdMps2: Float,
    val severeImpactThresholdMps2: Float,
    val rolloverAngleDeg: Float,
    val swerveLateralMps2: Float,
) {
    LOW("Conservative", 29.4f, 44.1f, 65.0f, 6.5f),
    STANDARD("Standard", 24.5f, 39.2f, 60.0f, 5.5f),
    HIGH("Sensitive", 21.6f, 35.3f, 55.0f, 4.8f),
}

data class AccidentAssessment(
    val eventClass: AccidentEventClass,
    val swerveClass: SwerveClass = SwerveClass.NORMAL_TURNING,
    val confidencePercent: Int,
    val timestampMillis: Long,
    val preEventSpeedKmh: Float?,
    val postEventSpeedKmh: Float?,
    val impactIntensityMps2: Float,
    val orientationChangeDeg: Float,
    val gpsAccuracyMeters: Float?,
    val sensorReliability: String,
    val triggeringMeasurements: List<String>,
    val missingEvidence: List<String>,
    val suppressedAsPhoneHandling: Boolean = false,
    val incidentId: String = "",
    val state: AccidentState = AccidentState.NORMAL,
    val peakImpactG: Float = impactIntensityMps2 / AccidentThresholdConfig.GRAVITY_MPS2,
    val evidenceGroups: List<EvidenceGroupStatus> = emptyList(),
    val independentGroupCount: Int = 0,
    val swerveCategory: SwerveBehaviorCategory = SwerveBehaviorCategory.NONE,
    val sensorQuality: SensorQualitySnapshot = SensorQualitySnapshot(),
    val isFallbackWarning: Boolean = false,
    val isSimulation: Boolean = false,
    val recalibrationRecommended: Boolean = false,
    val configVersion: String = AccidentThresholdConfig.DEFAULT_CONFIG_VERSION,
    val roadClassification: RoadEventClassification = RoadEventClassification.SMOOTH_DRIVING,
    val fiveSecondMotionObservation: String = "",
)

/**
 * Multi-sensor independent evidence confirmation engine.
 *
 * Groups measurements strictly by independent hardware/signal source:
 * - Group A: Phone Motion (IMU: accelerometer + gyroscope + rotation vector = ONE group)
 * - Group B: Vehicle Movement (independent GPS speed drop, independent OBD speed drop, unexpected stop)
 * - Group C: Vehicle Diagnostics (OBD RPM abrupt drop or ECU communication loss coinciding with impact)
 * - Group D: Driver Wellness (fresh BLE smartwatch HR deviation from known personal baseline)
 *
 * Ordinary automatic escalation requires a primary Group A motion event AND corroboration from
 * at least one genuinely independent group (B, C, or D).
 */
class AccidentConfidenceEvaluator {

    fun evaluate(
        timestampMillis: Long,
        peakLinearAccelMps2: Float,
        horizontalImpulseMps2: Float,
        verticalImpulseMps2: Float = 0f,
        sustainedLongitudinalDecelMps2: Float = 0f,
        sustainedImpactSamples: Int = 2,
        sustainedSevereSamples: Int = 0,
        motionEventDurationMs: Long = 100L,
        preEventGpsSpeedKmh: Float?,
        postEventGpsSpeedKmh: Float?,
        preEventObdSpeedKmh: Float? = null,
        postEventObdSpeedKmh: Float? = null,
        preEventObdRpm: Int? = null,
        postEventObdRpm: Int? = null,
        obdEcuLostDuringEvent: Boolean = false,
        watchHeartRateBpm: Int? = null,
        watchBaselineBpm: Float? = null,
        watchBaselineSampleCount: Int = 0,
        watchReadingAgeMs: Long? = null,
        watchSensorContact: Boolean? = null,
        rollChangeDeg: Float,
        pitchChangeDeg: Float,
        sustainedAbnormalOrientationMs: Long = 0L,
        peakAngularVelDegPerSec: Float,
        postImpactStationarySec: Float,
        postImpactStillnessSec: Float = 0f,
        mountCalibrationStatus: MountCalibrationStatus,
        gpsAccuracyMeters: Float?,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        obdConnected: Boolean,
        config: AccidentThresholdConfig = AccidentThresholdConfig(),
        impactEnabled: Boolean = true,
        rolloverEnabled: Boolean = true,
        inVerificationWindow: Boolean = false,
        verificationElapsedMs: Long = 0L,
        vehicleContinuedMovingNormally: Boolean = false,
        roadResult: RoughRoadResult = RoughRoadResult(RoadEventClassification.SMOOTH_DRIVING, 0, 1f, ""),
        incidentId: String = "",
        sensorQuality: SensorQualitySnapshot = SensorQualitySnapshot(),
    ): AccidentAssessment {
        val cfg = config.sanitized()
        val peakG = peakLinearAccelMps2 / AccidentThresholdConfig.GRAVITY_MPS2
        val orientationDelta = maxOf(abs(rollChangeDeg), abs(pitchChangeDeg))

        // Keep GPS speed and OBD speed strictly separated — never treat one copied from the other as independent
        val gpsReliable = preEventGpsSpeedKmh != null &&
            postEventGpsSpeedKmh != null &&
            (gpsAccuracyMeters == null || gpsAccuracyMeters <= 35f)
        val gpsSpeedDrop = if (gpsReliable) {
            (preEventGpsSpeedKmh!! - postEventGpsSpeedKmh!!).coerceAtLeast(0f)
        } else {
            null
        }
        val obdSpeedAvailable = obdConnected && preEventObdSpeedKmh != null && postEventObdSpeedKmh != null
        val obdSpeedDrop = if (obdSpeedAvailable) {
            (preEventObdSpeedKmh!! - postEventObdSpeedKmh!!).coerceAtLeast(0f)
        } else {
            null
        }

        val bestPreSpeed = preEventGpsSpeedKmh ?: preEventObdSpeedKmh
        val bestPostSpeed = postEventGpsSpeedKmh ?: postEventObdSpeedKmh
        val bestSpeedDrop = maxOf(gpsSpeedDrop ?: 0f, obdSpeedDrop ?: 0f)

        val triggers = mutableListOf<String>()
        val missing = mutableListOf<String>()

        if (!gyroAvailable) missing.add("Gyroscope unavailable")
        if (!gpsReliable) missing.add("Reliable GPS speed unavailable")
        if (!obdConnected) missing.add("OBD-II speed/RPM unavailable")
        val watchFresh = watchHeartRateBpm != null &&
            watchReadingAgeMs != null &&
            watchReadingAgeMs <= 15_000L &&
            watchSensorContact != false
        if (!watchFresh) missing.add("Fresh smartwatch heart rate unavailable")

        val sensorReliability = when {
            accelAvailable && gyroAvailable && (gpsReliable || obdConnected) -> "High"
            accelAvailable && (gyroAvailable || gpsReliable || obdConnected) -> "Moderate"
            else -> "Degraded"
        }

        // ---- False-Positive Guards ----
        val isVerticalRoadShock = (verticalImpulseMps2 > horizontalImpulseMps2 * 1.25f || roadResult.classification.isRoadDisturbance) &&
            bestSpeedDrop < cfg.abruptSpeedLossKmh &&
            orientationDelta < cfg.rolloverAngleDeg

        val isHarshBrakingOnly = sustainedLongitudinalDecelMps2 <= cfg.harshBrakingMps2 &&
            peakLinearAccelMps2 < cfg.strongImpactMps2 &&
            orientationDelta < cfg.rolloverAngleDeg

        val isSingleSampleSpike = sustainedImpactSamples < 2 && motionEventDurationMs < cfg.minImpactDurationMs

        val isStationaryPhoneRotation = (bestPreSpeed ?: 0f) < 8f &&
            (bestPostSpeed ?: 0f) < 8f &&
            (orientationDelta >= 28f || peakAngularVelDegPerSec >= 75f) &&
            horizontalImpulseMps2 < cfg.strongImpactMps2

        val isMovingPhoneHandling = (bestPreSpeed ?: 0f) >= 8f &&
            bestSpeedDrop < 10f &&
            (orientationDelta >= 32f || peakAngularVelDegPerSec >= 100f) &&
            horizontalImpulseMps2 < cfg.strongImpactMps2 * 0.8f

        val likelyPhoneHandling = mountCalibrationStatus == MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED ||
            isStationaryPhoneRotation ||
            isMovingPhoneHandling

        // ---- GROUP A: Phone Motion (IMU) — accel + gyro + rotation vector are ONE group ----
        val groupADetails = mutableListOf<String>()
        val strongImpact = impactEnabled &&
            !isSingleSampleSpike &&
            !isVerticalRoadShock &&
            peakLinearAccelMps2 >= cfg.strongImpactMps2
        val severeImpact = impactEnabled &&
            !isSingleSampleSpike &&
            !isVerticalRoadShock &&
            peakLinearAccelMps2 >= cfg.severeImpactMps2
        val rolloverTriggered = rolloverEnabled &&
            !likelyPhoneHandling &&
            orientationDelta >= cfg.rolloverAngleDeg &&
            (sustainedAbnormalOrientationMs >= 400L || peakAngularVelDegPerSec >= 90f) &&
            ((bestPreSpeed ?: 0f) >= 12f || peakLinearAccelMps2 >= cfg.strongImpactMps2 * 0.85f)

        if (severeImpact) {
            groupADetails.add("Severe sustained impact ${"%.2f".format(peakG)}g (${"%.1f".format(peakLinearAccelMps2)} m/s², ${motionEventDurationMs}ms)")
        } else if (strongImpact) {
            groupADetails.add("Strong sustained impact ${"%.2f".format(peakG)}g (${"%.1f".format(peakLinearAccelMps2)} m/s², ${motionEventDurationMs}ms)")
        }
        if (rolloverTriggered) {
            groupADetails.add("Vehicle-relative tilt ${"%.0f".format(orientationDelta)}° (${"%.0f".format(peakAngularVelDegPerSec)}°/s)")
        }
        val groupACorroborated = !likelyPhoneHandling && (strongImpact || severeImpact || rolloverTriggered)
        val groupA = EvidenceGroupStatus(
            group = EvidenceGroup.GROUP_A_PHONE_MOTION,
            corroborated = groupACorroborated,
            available = accelAvailable,
            stale = false,
            details = groupADetails.ifEmpty {
                when {
                    likelyPhoneHandling -> listOf("Suppressed: phone handling or mount shift detected")
                    isSingleSampleSpike -> listOf("Ignored: transient single-sample spike (${motionEventDurationMs}ms)")
                    isVerticalRoadShock -> listOf("Classified as road bump/pothole (vertical shock)")
                    isHarshBrakingOnly -> listOf("Classified as harsh braking (${"%.1f".format(sustainedLongitudinalDecelMps2)} m/s²)")
                    else -> listOf("Normal motion (${"%.2f".format(peakG)}g)")
                }
            },
        )

        // ---- GROUP B: Vehicle Movement (GPS speed / OBD speed / unexpected stop) ----
        val groupBDetails = mutableListOf<String>()
        val groupBAvailable = gpsReliable || obdSpeedAvailable
        var groupBCorroborated = false
        if (gpsSpeedDrop != null && gpsSpeedDrop >= cfg.abruptSpeedLossKmh && (preEventGpsSpeedKmh ?: 0f) >= cfg.abruptSpeedLossKmh) {
            groupBCorroborated = true
            groupBDetails.add("GPS abrupt speed loss -${"%.0f".format(gpsSpeedDrop)} km/h (${preEventGpsSpeedKmh?.toInt()}→${postEventGpsSpeedKmh?.toInt()} km/h)")
        }
        if (obdSpeedDrop != null && obdSpeedDrop >= cfg.abruptSpeedLossKmh && (preEventObdSpeedKmh ?: 0f) >= cfg.abruptSpeedLossKmh) {
            groupBCorroborated = true
            groupBDetails.add("OBD abrupt speed loss -${"%.0f".format(obdSpeedDrop)} km/h (${preEventObdSpeedKmh?.toInt()}→${postEventObdSpeedKmh?.toInt()} km/h)")
        }
        if (postImpactStationarySec >= 2.5f && (bestPreSpeed ?: 0f) >= 20f && (bestPostSpeed ?: 99f) < 3f) {
            groupBCorroborated = true
            groupBDetails.add("Vehicle stopped unexpectedly for ${"%.1f".format(postImpactStationarySec)}s")
        }
        val groupB = EvidenceGroupStatus(
            group = EvidenceGroup.GROUP_B_VEHICLE_MOVEMENT,
            corroborated = groupBCorroborated,
            available = groupBAvailable,
            stale = !groupBAvailable,
            details = groupBDetails.ifEmpty {
                listOf(if (groupBAvailable) "No abrupt vehicle speed loss" else "GPS/OBD speed unavailable")
            },
        )

        // ---- GROUP C: Vehicle Diagnostics (OBD RPM abrupt drop or ECU loss coinciding with impact) ----
        val groupCDetails = mutableListOf<String>()
        val groupCAvailable = obdConnected || obdEcuLostDuringEvent
        var groupCCorroborated = false
        if (obdConnected && preEventObdRpm != null && postEventObdRpm != null) {
            val rpmDrop = preEventObdRpm - postEventObdRpm
            if ((preEventObdRpm >= 1100 && postEventObdRpm <= 250) || rpmDrop >= 1400) {
                groupCCorroborated = true
                groupCDetails.add("Abrupt OBD RPM drop ($preEventObdRpm→$postEventObdRpm RPM)")
            }
        }
        if (obdEcuLostDuringEvent && groupACorroborated) {
            groupCCorroborated = true
            groupCDetails.add("ECU communication lost during impact window")
        }
        val groupC = EvidenceGroupStatus(
            group = EvidenceGroup.GROUP_C_VEHICLE_DIAGNOSTICS,
            corroborated = groupCCorroborated,
            available = groupCAvailable,
            stale = !groupCAvailable,
            details = groupCDetails.ifEmpty {
                listOf(if (obdConnected) "ECU telemetry nominal" else "OBD-II diagnostics unavailable")
            },
        )

        // ---- GROUP D: Driver Wellness (Fresh Smartwatch HR deviation from known personal baseline) ----
        val groupDDetails = mutableListOf<String>()
        val baselineValid = watchBaselineBpm != null && watchBaselineSampleCount >= 10
        var groupDCorroborated = false
        if (watchFresh && baselineValid) {
            val deltaBpm = abs(watchHeartRateBpm!! - watchBaselineBpm!!)
            val thresholdBpm = maxOf(25f, watchBaselineBpm * 0.25f)
            if (deltaBpm >= thresholdBpm) {
                groupDCorroborated = true
                groupDDetails.add("Watch HR deviation: ${watchHeartRateBpm} BPM vs ${watchBaselineBpm.toInt()} BPM baseline")
            }
        }
        val groupD = EvidenceGroupStatus(
            group = EvidenceGroup.GROUP_D_DRIVER_WELLNESS,
            corroborated = groupDCorroborated,
            available = watchFresh,
            stale = !watchFresh,
            details = groupDDetails.ifEmpty {
                when {
                    !watchFresh -> listOf("Fresh smartwatch heart rate unavailable (non-blocking)")
                    !baselineValid -> listOf("Watch HR ${watchHeartRateBpm} BPM (baseline calibrating)")
                    else -> listOf("Watch HR ${watchHeartRateBpm} BPM within personal baseline")
                }
            },
        )

        val allGroups = listOf(groupA, groupB, groupC, groupD)
        val independentCorroboratingCount = listOf(groupB, groupC, groupD).count { it.corroborated }
        val allIndependentUnavailable = !groupB.available && !groupC.available && !groupD.available

        // Conservative SEVERE_EVENT_FALLBACK when ALL independent sensors are unavailable:
        // Requires calibrated mount, sustained multi-sample severe impact >= 4.0g over >= 120ms,
        // and post-impact stillness >= 2.0s. Never fires on a single spike or uncalibrated mount.
        val severeFallbackQualified = groupACorroborated &&
            allIndependentUnavailable &&
            mountCalibrationStatus == MountCalibrationStatus.CALIBRATED &&
            severeImpact &&
            sustainedSevereSamples >= 2 &&
            motionEventDurationMs >= 120L &&
            postImpactStillnessSec >= 2.0f

        // Collect explainable trigger reasons
        allGroups.filter { it.corroborated }.forEach { g ->
            triggers.addAll(g.details)
        }
        if (severeFallbackQualified) {
            triggers.add("Fallback: calibrated mount + sustained severe impact (>=${cfg.severeImpactG}g) + ${"%.1f".format(postImpactStillnessSec)}s post-impact stillness (independent sensors unavailable)")
        }

        // Compute deterministic state & event class
        val finalState: AccidentState
        val finalClass: AccidentEventClass
        var confidence = 0

        val observationText = when {
            inVerificationWindow -> "Verifying (${verificationElapsedMs / 1000L}s/5s): ${roadResult.classification.label}"
            vehicleContinuedMovingNormally -> "Vehicle continued moving normally (${"%.0f".format(bestPostSpeed ?: 0f)} km/h); ${roadResult.classification.label}"
            postImpactStationarySec > 0f -> "Stationary ${"%.1f".format(postImpactStationarySec)}s post-event; ${roadResult.classification.label}"
            else -> roadResult.classification.label
        }

        val severeImmediateRollover = rolloverTriggered &&
            sustainedAbnormalOrientationMs >= 600L &&
            peakLinearAccelMps2 >= cfg.severeImpactMps2

        when {
            likelyPhoneHandling -> {
                finalState = AccidentState.DRIVING_EVENT_ONLY
                finalClass = AccidentEventClass.MINOR_ROAD_IMPACT
                confidence = 10
            }
            severeFallbackQualified -> {
                finalState = AccidentState.SEVERE_EVENT_FALLBACK
                finalClass = AccidentEventClass.SUSPECTED_COLLISION
                confidence = 68
            }
            inVerificationWindow && !severeImmediateRollover -> {
                finalState = AccidentState.VERIFYING
                finalClass = AccidentEventClass.SIGNIFICANT_IMPACT
                confidence = if (groupACorroborated) 42 else 22
            }
            verificationElapsedMs >= 5_000L && vehicleContinuedMovingNormally && !rolloverTriggered && !(severeImpact && independentCorroboratingCount >= 1) -> {
                finalState = AccidentState.DISMISSED_ROAD_EVENT
                finalClass = AccidentEventClass.MINOR_ROAD_IMPACT
                confidence = 15
                triggers.add("Dismissed after 5s verification: vehicle continued moving normally (${roadResult.classification.label})")
            }
            groupACorroborated && independentCorroboratingCount >= 1 -> {
                finalState = AccidentState.POSSIBLE_ACCIDENT
                confidence = (55 + (if (severeImpact) 20 else 10) + independentCorroboratingCount * 15).coerceIn(60, 98)
                finalClass = when {
                    rolloverTriggered -> if (confidence >= 85) AccidentEventClass.HIGH_CONFIDENCE_POSSIBLE_ACCIDENT else AccidentEventClass.SUSPECTED_ROLLOVER
                    confidence >= 85 -> AccidentEventClass.HIGH_CONFIDENCE_POSSIBLE_ACCIDENT
                    else -> AccidentEventClass.SUSPECTED_COLLISION
                }
            }
            isHarshBrakingOnly || isVerticalRoadShock || groupACorroborated -> {
                finalState = AccidentState.DRIVING_EVENT_ONLY
                finalClass = if (peakLinearAccelMps2 >= cfg.strongImpactMps2 * 0.6f) {
                    AccidentEventClass.SIGNIFICANT_IMPACT
                } else {
                    AccidentEventClass.MINOR_ROAD_IMPACT
                }
                confidence = if (groupACorroborated) 35 else 18
            }
            peakLinearAccelMps2 >= cfg.minorMovementMps2 * 2f -> {
                finalState = AccidentState.NORMAL
                finalClass = AccidentEventClass.MINOR_ROAD_IMPACT
                confidence = 8
            }
            else -> {
                finalState = AccidentState.NORMAL
                finalClass = AccidentEventClass.NORMAL_DRIVING
                confidence = 0
            }
        }

        return AccidentAssessment(
            eventClass = finalClass,
            confidencePercent = confidence,
            timestampMillis = timestampMillis,
            preEventSpeedKmh = bestPreSpeed,
            postEventSpeedKmh = bestPostSpeed,
            impactIntensityMps2 = peakLinearAccelMps2,
            orientationChangeDeg = orientationDelta,
            gpsAccuracyMeters = gpsAccuracyMeters,
            sensorReliability = sensorReliability,
            triggeringMeasurements = triggers.ifEmpty { groupA.details },
            missingEvidence = missing,
            suppressedAsPhoneHandling = likelyPhoneHandling,
            incidentId = incidentId,
            state = finalState,
            peakImpactG = peakG,
            evidenceGroups = allGroups,
            independentGroupCount = independentCorroboratingCount,
            sensorQuality = sensorQuality,
            isFallbackWarning = severeFallbackQualified,
            recalibrationRecommended = likelyPhoneHandling || mountCalibrationStatus == MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED,
            configVersion = cfg.configVersion,
            roadClassification = roadResult.classification,
            fiveSecondMotionObservation = observationText,
        )
    }
}
