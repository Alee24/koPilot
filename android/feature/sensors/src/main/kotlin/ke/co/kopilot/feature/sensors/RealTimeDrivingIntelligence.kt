package ke.co.kopilot.feature.sensors

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ActiveDrivingState(val label: String) {
    PARKED("Parked"),
    IDLING("Idling"),
    CRUISING("Cruising"),
    ACCELERATING("Accelerating"),
    BRAKING("Braking"),
    CORNERING("Cornering"),
}

enum class DrivingEventType(val label: String, val isSafetyCritical: Boolean) {
    HARSH_BRAKING("Harsh Braking", true),
    AGGRESSIVE_ACCELERATION("Aggressive Acceleration", true),
    SHARP_CORNERING("Sharp Cornering", true),
    EXCESSIVE_SPEED("Overspeeding", true),
    EXCESSIVE_IDLING("Extended Idling", false),
    HIGH_RPM_DRIVING("High-RPM Shifting", false),
    SPEED_VARIABILITY("Unsteady Speed", false),
    POTHOLE_IMPACT("Road Impact", true),
}

enum class EventSeverity {
    INFO,
    MODERATE,
    HIGH,
}

data class DrivingIntelligenceEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: DrivingEventType,
    val severity: EventSeverity,
    val title: String,
    val detail: String,
    val speedKmh: Float?,
    val safetyImpact: Int,
    val fuelImpact: Int,
    val timestampMillis: Long,
)

data class IntelligenceScorePoint(
    val timestampMillis: Long,
    val safetyScore: Int,
    val fuelScore: Int,
)

data class ScoringFactorBreakdown(
    val label: String,
    val detail: String,
    val pointsDelta: Int,
)

data class LocalCoachingAdvice(
    val id: String,
    val category: String, // "Safety" or "Fuel"
    val message: String,
)

data class RealTimeIntelligenceInput(
    val nowMillis: Long,
    val isJourneyActive: Boolean,
    val motionAnalysis: DrivingMotionAnalysis,
    val gpsSpeedKmh: Float?,
    val speedLimitKmh: Int?,
    val obdConnected: Boolean,
    val obdRpm: Int?,
    val obdThrottlePct: Float?,
    val obdEngineLoadPct: Float?,
    val obdMafGramsPerSec: Float?,
)

data class RealTimeDrivingIntelligenceState(
    val safetyScore: Int = 100,
    val fuelEfficiencyScore: Int = 100,
    val drivingState: ActiveDrivingState = ActiveDrivingState.PARKED,
    val latestEvent: DrivingIntelligenceEvent? = null,
    val recentEvents: List<DrivingIntelligenceEvent> = emptyList(),
    val scoreTrend: List<IntelligenceScorePoint> = emptyList(),
    val scoringBreakdown: List<ScoringFactorBreakdown> = emptyList(),
    val coachingAdvice: List<LocalCoachingAdvice> = listOf(
        LocalCoachingAdvice("init_1", "Safety", "Driving intelligence active. Smooth inputs keep safety and fuel scores high."),
    ),
    val idlingSeconds: Long = 0L,
    val movingSeconds: Long = 0L,
    val idlingRatioPct: Int = 0,
    val speedVariabilityKmh: Float = 0f,
    val harshBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val sharpCornerCount: Int = 0,
    val overspeedEventCount: Int = 0,
    val highRpmShiftCount: Int = 0,
    val potholeImpactCount: Int = 0,
    val updatedAtMillis: Long = 0L,
)

/**
 * Pure-Kotlin, offline-first Real-Time Driving Intelligence engine.
 * Aggregates GPS speed, accelerometer/gyroscope motion analysis, and optional OBD-II telemetry
 * using confirmation windows and smoothed scoring so single sensor spikes never cause erratic jumps.
 */
@Singleton
class RealTimeDrivingIntelligence @Inject constructor() {

    private val _state = MutableStateFlow(RealTimeDrivingIntelligenceState())
    val state: StateFlow<RealTimeDrivingIntelligenceState> = _state.asStateFlow()

    private val events = ArrayDeque<DrivingIntelligenceEvent>(MAX_EVENTS)
    private val trend = ArrayDeque<IntelligenceScorePoint>(MAX_TREND_POINTS)
    private val cruiseSpeedWindow = ArrayDeque<Float>(SPEED_WINDOW_SIZE)

    private var lastTickMs = 0L
    private var lastTrendSampleMs = 0L

    // Confirmation / cooldown timestamps
    private var lastHarshBrakeEventMs = 0L
    private var lastHardAccelEventMs = 0L
    private var lastSharpCornerEventMs = 0L
    private var lastPotholeCount = 0
    private var overspeedSinceMs = 0L
    private var lastOverspeedEventMs = 0L
    private var highRpmSinceMs = 0L
    private var lastHighRpmEventMs = 0L
    private var lastIdlingAlertMs = 0L
    private var lastVariabilityAlertMs = 0L
    private var cleanMovingSeconds = 0f

    // Accumulated counters
    private var harshBrakeCount = 0
    private var hardAccelCount = 0
    private var sharpCornerCount = 0
    private var overspeedCount = 0
    private var highRpmCount = 0
    private var potholeImpactCount = 0
    private var recoveryBonus = 0

    private var idlingSecAccum = 0f
    private var movingSecAccum = 0f
    private var smoothedSafety = 100f
    private var smoothedFuel = 100f

    @Synchronized
    fun resetForNewJourney(nowMillis: Long = System.currentTimeMillis()) {
        events.clear()
        trend.clear()
        cruiseSpeedWindow.clear()
        lastTickMs = nowMillis
        lastTrendSampleMs = 0L
        lastHarshBrakeEventMs = 0L
        lastHardAccelEventMs = 0L
        lastSharpCornerEventMs = 0L
        lastPotholeCount = 0
        overspeedSinceMs = 0L
        lastOverspeedEventMs = 0L
        highRpmSinceMs = 0L
        lastHighRpmEventMs = 0L
        lastIdlingAlertMs = 0L
        lastVariabilityAlertMs = 0L
        cleanMovingSeconds = 0f
        harshBrakeCount = 0
        hardAccelCount = 0
        sharpCornerCount = 0
        overspeedCount = 0
        highRpmCount = 0
        potholeImpactCount = 0
        recoveryBonus = 0
        idlingSecAccum = 0f
        movingSecAccum = 0f
        smoothedSafety = 100f
        smoothedFuel = 100f
        _state.value = RealTimeDrivingIntelligenceState(updatedAtMillis = nowMillis)
    }

    /**
     * Processes one intelligence tick and returns any newly confirmed event for immediate logging/voice.
     */
    @Synchronized
    fun update(input: RealTimeIntelligenceInput): DrivingIntelligenceEvent? {
        val now = input.nowMillis
        val dtSec = if (lastTickMs > 0L) {
            ((now - lastTickMs) / 1000f).coerceIn(0.05f, 2.0f)
        } else {
            0.25f
        }
        lastTickMs = now

        val speed = input.gpsSpeedKmh ?: 0f
        val motion = input.motionAnalysis
        val isMoving = speed >= 4.0f
        val engineRunning = (input.obdRpm ?: 0) > 400

        val drivingState = when {
            !input.isJourneyActive && !isMoving -> ActiveDrivingState.PARKED
            !isMoving && (engineRunning || input.isJourneyActive) -> ActiveDrivingState.IDLING
            motion.lateralState == LateralMotionState.SHARP_CORNERING ||
                motion.lateralState == LateralMotionState.LEFT_TURN ||
                motion.lateralState == LateralMotionState.RIGHT_TURN -> ActiveDrivingState.CORNERING
            motion.longitudinalState == LongitudinalMotionState.BRAKING ||
                motion.longitudinalState == LongitudinalMotionState.HARSH_BRAKING -> ActiveDrivingState.BRAKING
            motion.longitudinalState == LongitudinalMotionState.ACCELERATING ||
                motion.longitudinalState == LongitudinalMotionState.HARD_ACCELERATION -> ActiveDrivingState.ACCELERATING
            else -> ActiveDrivingState.CRUISING
        }

        var newlyConfirmedEvent: DrivingIntelligenceEvent? = null

        if (input.isJourneyActive) {
            val hadPriorCruiseSpeeds = cruiseSpeedWindow.isNotEmpty()
            val recentPeakSpeedKmh = cruiseSpeedWindow.maxOrNull() ?: speed
            val speedReducedForBrake = !hadPriorCruiseSpeeds || (recentPeakSpeedKmh - speed) >= 4.0f

            if (isMoving) {
                movingSecAccum += dtSec
                if (cruiseSpeedWindow.size >= SPEED_WINDOW_SIZE) cruiseSpeedWindow.removeFirst()
                cruiseSpeedWindow.addLast(speed)
            } else if (engineRunning || input.isJourneyActive) {
                idlingSecAccum += dtSec
            }

            // 1. Confirmed Harsh Braking (requires both speed reduction and longitudinal braking forces)
            if (motion.longitudinalState == LongitudinalMotionState.HARSH_BRAKING &&
                speedReducedForBrake &&
                motion.longitudinalAccelMps2 <= DrivingMotionAnalyzer.HARSH_BRAKE_MPS2 &&
                (lastHarshBrakeEventMs == 0L || (now - lastHarshBrakeEventMs) >= EVENT_COOLDOWN_MS)
            ) {
                lastHarshBrakeEventMs = now
                harshBrakeCount++
                cleanMovingSeconds = 0f
                newlyConfirmedEvent = recordEvent(
                    DrivingIntelligenceEvent(
                        type = DrivingEventType.HARSH_BRAKING,
                        severity = EventSeverity.HIGH,
                        title = "Harsh Braking",
                        detail = "Sudden braking — please keep a safe following distance.",
                        speedKmh = input.gpsSpeedKmh,
                        safetyImpact = -5,
                        fuelImpact = -3,
                        timestampMillis = now,
                    ),
                )
            }

            // 2. Confirmed Aggressive Acceleration
            if (newlyConfirmedEvent == null &&
                motion.longitudinalState == LongitudinalMotionState.HARD_ACCELERATION &&
                (lastHardAccelEventMs == 0L || (now - lastHardAccelEventMs) >= EVENT_COOLDOWN_MS)
            ) {
                lastHardAccelEventMs = now
                hardAccelCount++
                cleanMovingSeconds = 0f
                newlyConfirmedEvent = recordEvent(
                    DrivingIntelligenceEvent(
                        type = DrivingEventType.AGGRESSIVE_ACCELERATION,
                        severity = EventSeverity.MODERATE,
                        title = "Aggressive Acceleration",
                        detail = "Hard throttle surge (${"%.1f".format(motion.longitudinalAccelMps2)} m/s²)",
                        speedKmh = input.gpsSpeedKmh,
                        safetyImpact = -3,
                        fuelImpact = -4,
                        timestampMillis = now,
                    ),
                )
            }

            // 3. Confirmed Sharp Cornering
            if (newlyConfirmedEvent == null &&
                motion.lateralState == LateralMotionState.SHARP_CORNERING &&
                (lastSharpCornerEventMs == 0L || (now - lastSharpCornerEventMs) >= EVENT_COOLDOWN_MS)
            ) {
                lastSharpCornerEventMs = now
                sharpCornerCount++
                cleanMovingSeconds = 0f
                newlyConfirmedEvent = recordEvent(
                    DrivingIntelligenceEvent(
                        type = DrivingEventType.SHARP_CORNERING,
                        severity = EventSeverity.HIGH,
                        title = "Sharp Cornering",
                        detail = "Sharp turn detected — please slow down through corners.",
                        speedKmh = input.gpsSpeedKmh,
                        safetyImpact = -4,
                        fuelImpact = -2,
                        timestampMillis = now,
                    ),
                )
            }

            // 4. Pothole / Road Impact (only while moving >= 8 km/h; polite message without raw RMS decimals)
            val currentPotholes = motion.behaviourStats.potholeBumpCount
            if (newlyConfirmedEvent == null && speed >= 8.0f && currentPotholes > lastPotholeCount) {
                lastPotholeCount = currentPotholes
                potholeImpactCount++
                newlyConfirmedEvent = recordEvent(
                    DrivingIntelligenceEvent(
                        type = DrivingEventType.POTHOLE_IMPACT,
                        severity = EventSeverity.MODERATE,
                        title = "Bump Hit",
                        detail = "Bump hit — please slow down and be watchful.",
                        speedKmh = input.gpsSpeedKmh,
                        safetyImpact = -1,
                        fuelImpact = 0,
                        timestampMillis = now,
                    ),
                )
            }

            // 5. Confirmed Overspeeding (> limit + 5 km/h for >= 3s)
            val limit = input.speedLimitKmh
            if (limit != null && speed > (limit + 5f)) {
                if (overspeedSinceMs == 0L) overspeedSinceMs = now
                if (newlyConfirmedEvent == null &&
                    (now - overspeedSinceMs) >= 3_000L &&
                    (lastOverspeedEventMs == 0L || (now - lastOverspeedEventMs) >= 20_000L)
                ) {
                    lastOverspeedEventMs = now
                    overspeedCount++
                    cleanMovingSeconds = 0f
                    newlyConfirmedEvent = recordEvent(
                        DrivingIntelligenceEvent(
                            type = DrivingEventType.EXCESSIVE_SPEED,
                            severity = EventSeverity.HIGH,
                            title = "Overspeeding",
                            detail = "${speed.roundToInt()} km/h in $limit km/h zone",
                            speedKmh = input.gpsSpeedKmh,
                            safetyImpact = -5,
                            fuelImpact = -3,
                            timestampMillis = now,
                        ),
                    )
                }
            } else {
                overspeedSinceMs = 0L
            }

            // 6. Confirmed High-RPM Shifting (OBD-II supported)
            val rpm = input.obdRpm
            if (input.obdConnected && rpm != null && rpm >= 3_500 && isMoving) {
                if (highRpmSinceMs == 0L) highRpmSinceMs = now
                if (newlyConfirmedEvent == null &&
                    (now - highRpmSinceMs) >= 1_800L &&
                    (lastHighRpmEventMs == 0L || (now - lastHighRpmEventMs) >= 15_000L)
                ) {
                    lastHighRpmEventMs = now
                    highRpmCount++
                    newlyConfirmedEvent = recordEvent(
                        DrivingIntelligenceEvent(
                            type = DrivingEventType.HIGH_RPM_DRIVING,
                            severity = EventSeverity.MODERATE,
                            title = "High-RPM Driving",
                            detail = "Engine at $rpm RPM — shift up or ease throttle",
                            speedKmh = input.gpsSpeedKmh,
                            safetyImpact = 0,
                            fuelImpact = -4,
                            timestampMillis = now,
                        ),
                    )
                }
            } else {
                highRpmSinceMs = 0L
            }

            // 7. Extended Idling check (every 60s of idling)
            if (idlingSecAccum >= 60f && (lastIdlingAlertMs == 0L || (now - lastIdlingAlertMs) >= 60_000L) && !isMoving) {
                lastIdlingAlertMs = now
                if (newlyConfirmedEvent == null) {
                    newlyConfirmedEvent = recordEvent(
                        DrivingIntelligenceEvent(
                            type = DrivingEventType.EXCESSIVE_IDLING,
                            severity = EventSeverity.INFO,
                            title = "Extended Idling",
                            detail = "${idlingSecAccum.toLong()}s stationary idling",
                            speedKmh = input.gpsSpeedKmh,
                            safetyImpact = 0,
                            fuelImpact = -3,
                            timestampMillis = now,
                        ),
                    )
                }
            }

            // Clean moving recovery (+1 every 30s of clean driving >= 15 km/h)
            if (speed >= 15f && newlyConfirmedEvent == null) {
                cleanMovingSeconds += dtSec
                if (cleanMovingSeconds >= 30f) {
                    cleanMovingSeconds = 0f
                    recoveryBonus = (recoveryBonus + 1).coerceAtMost(15)
                }
            }
        }

        // Compute Speed Variability (std-dev of cruising window)
        val speedStdDev = computeStdDev(cruiseSpeedWindow)
        val totalJourneySec = (idlingSecAccum + movingSecAccum).coerceAtLeast(1f)
        val idlingRatioPct = ((idlingSecAccum / totalJourneySec) * 100f).roundToInt().coerceIn(0, 100)

        // Transparent Safety Score (0..100)
        val brakePenalty = harshBrakeCount * 5
        val accelSafetyPenalty = hardAccelCount * 3
        val cornerPenalty = sharpCornerCount * 4
        val overspeedPenalty = overspeedCount * 5
        val impactPenalty = (potholeImpactCount / 2) * 1
        val rawSafetyTarget = (100 - brakePenalty - accelSafetyPenalty - cornerPenalty - overspeedPenalty - impactPenalty + recoveryBonus)
            .coerceIn(10, 100)

        // Transparent Fuel Efficiency Score (0..100)
        val fuelAccelPenalty = hardAccelCount * 4
        val fuelBrakePenalty = harshBrakeCount * 3
        val idlingPenalty = when {
            idlingRatioPct >= 35 -> 16
            idlingRatioPct >= 20 -> 10
            idlingRatioPct >= 10 -> 5
            else -> 0
        }
        val variabilityPenalty = when {
            speedStdDev >= 16f -> 12
            speedStdDev >= 10f -> 6
            else -> 0
        }
        val highRpmPenalty = highRpmCount * 4
        val obdLoadPenalty = if (input.obdConnected && (input.obdEngineLoadPct ?: 0f) > 85f) 4 else 0
        val rawFuelTarget = (100 - fuelAccelPenalty - fuelBrakePenalty - idlingPenalty - variabilityPenalty - highRpmPenalty - obdLoadPenalty)
            .coerceIn(10, 100)

        // Smooth score transitions (immediate step on new event, gentle filter otherwise)
        val alpha = if (newlyConfirmedEvent != null) 0.75f else (dtSec * 0.6f).coerceIn(0.05f, 0.35f)
        smoothedSafety += (rawSafetyTarget - smoothedSafety) * alpha
        smoothedFuel += (rawFuelTarget - smoothedFuel) * alpha

        val finalSafety = smoothedSafety.roundToInt().coerceIn(10, 100)
        val finalFuel = smoothedFuel.roundToInt().coerceIn(10, 100)

        if (input.isJourneyActive && (lastTrendSampleMs == 0L || (now - lastTrendSampleMs) >= 3_000L)) {
            lastTrendSampleMs = now
            if (trend.size >= MAX_TREND_POINTS) trend.removeFirst()
            trend.addLast(IntelligenceScorePoint(now, finalSafety, finalFuel))
        }

        val breakdown = listOf(
            ScoringFactorBreakdown("Harsh Braking ($harshBrakeCount)", "Sudden deceleration events", -brakePenalty),
            ScoringFactorBreakdown("Aggressive Accel ($hardAccelCount)", "Hard throttle launches", -(fuelAccelPenalty + accelSafetyPenalty) / 2),
            ScoringFactorBreakdown("Sharp Cornering ($sharpCornerCount)", "High lateral G / yaw turns", -cornerPenalty),
            ScoringFactorBreakdown("Idling Ratio ($idlingRatioPct%)", "${idlingSecAccum.toLong()}s stationary", -idlingPenalty),
            ScoringFactorBreakdown("Speed Variability", "Std-dev ${"%.1f".format(speedStdDev)} km/h", -variabilityPenalty),
            ScoringFactorBreakdown("High-RPM Shifts ($highRpmCount)", "OBD-II > 3500 RPM", -highRpmPenalty),
        )

        val advice = buildLocalAdvice(
            safetyScore = finalSafety,
            fuelScore = finalFuel,
            idlingRatioPct = idlingRatioPct,
            speedStdDev = speedStdDev,
            obdRpm = input.obdRpm,
        )

        _state.value = RealTimeDrivingIntelligenceState(
            safetyScore = finalSafety,
            fuelEfficiencyScore = finalFuel,
            drivingState = drivingState,
            latestEvent = events.firstOrNull(),
            recentEvents = events.toList(),
            scoreTrend = trend.toList(),
            scoringBreakdown = breakdown,
            coachingAdvice = advice,
            idlingSeconds = idlingSecAccum.toLong(),
            movingSeconds = movingSecAccum.toLong(),
            idlingRatioPct = idlingRatioPct,
            speedVariabilityKmh = speedStdDev,
            harshBrakeCount = harshBrakeCount,
            hardAccelCount = hardAccelCount,
            sharpCornerCount = sharpCornerCount,
            overspeedEventCount = overspeedCount,
            highRpmShiftCount = highRpmCount,
            potholeImpactCount = potholeImpactCount,
            updatedAtMillis = now,
        )

        return newlyConfirmedEvent
    }

    private fun recordEvent(event: DrivingIntelligenceEvent): DrivingIntelligenceEvent {
        if (events.size >= MAX_EVENTS) events.removeLast()
        events.addFirst(event)
        return event
    }

    private fun computeStdDev(values: Collection<Float>): Float {
        if (values.size < 5) return 0f
        val mean = values.average().toFloat()
        var sumSq = 0f
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }

    private fun buildLocalAdvice(
        safetyScore: Int,
        fuelScore: Int,
        idlingRatioPct: Int,
        speedStdDev: Float,
        obdRpm: Int?,
    ): List<LocalCoachingAdvice> {
        val tips = mutableListOf<LocalCoachingAdvice>()
        if (harshBrakeCount > 0) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_brake",
                    "Safety",
                    "$harshBrakeCount harsh braking event(s) — increase following distance and anticipate stops earlier.",
                ),
            )
        }
        if (hardAccelCount > 0) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_accel",
                    "Fuel",
                    "$hardAccelCount aggressive acceleration(s) — roll onto the throttle smoothly to cut fuel waste.",
                ),
            )
        }
        if (sharpCornerCount > 0) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_corner",
                    "Safety",
                    "$sharpCornerCount sharp cornering event(s) — brake before turns rather than mid-corner.",
                ),
            )
        }
        if (idlingRatioPct >= 15) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_idle",
                    "Fuel",
                    "Idling accounts for $idlingRatioPct% of this trip — switch off the engine during long stops.",
                ),
            )
        }
        if (speedStdDev >= 10f) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_var",
                    "Fuel",
                    "Cruising speed varies by ±${"%.0f".format(speedStdDev)} km/h — maintaining a steady pace improves fuel economy.",
                ),
            )
        }
        if (obdRpm != null && obdRpm >= 3200) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_rpm",
                    "Fuel",
                    "High engine RPM ($obdRpm) — shift up early to keep engine in its efficient torque band.",
                ),
            )
        }
        if (tips.isEmpty()) {
            tips.add(
                LocalCoachingAdvice(
                    "adv_good",
                    if (safetyScore >= fuelScore) "Safety" else "Fuel",
                    "Smooth, consistent driving (Safety $safetyScore/100, Fuel $fuelScore/100). Keep it up.",
                ),
            )
        }
        return tips.take(4)
    }

    companion object {
        private const val MAX_EVENTS = 60
        private const val MAX_TREND_POINTS = 90
        private const val SPEED_WINDOW_SIZE = 30
        private const val EVENT_COOLDOWN_MS = 3_000L
    }
}
