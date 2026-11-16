package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import ke.co.kopilot.core.common.model.TrafficSignObservation
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RawSignDetection(
    val category: TrafficSignCategory,
    val confidence: Float,
    val normalizedCenterX: Float, // 0.0 (left) to 1.0 (right)
    val customLabel: String? = null,
)

@Singleton
class SignTemporalTracker @Inject constructor() {

    private var candidateCategory: TrafficSignCategory? = null
    private var consecutiveHits = 0
    private var missedFrames = 0
    private var maxConfidence = 0f
    private val lastAnnouncedMs = mutableMapOf<TrafficSignCategory, Long>()

    fun processFrame(
        detections: List<RawSignDetection>,
        modelVersion: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): TrafficSignObservation? {
        val best = detections
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }

        if (best == null) {
            missedFrames += 1
            if (missedFrames > 1) {
                consecutiveHits = 0
                candidateCategory = null
                maxConfidence = 0f
            }
            return null
        }

        missedFrames = 0
        if (best.category == candidateCategory) {
            consecutiveHits += 1
            maxConfidence = maxOf(maxConfidence, best.confidence)
        } else {
            candidateCategory = best.category
            consecutiveHits = 1
            maxConfidence = best.confidence
        }

        // High-confidence (>= 85%) clear sign confirms immediately in 1 frame for fast-moving cars;
        // lower-confidence detections require 2 frames.
        val requiredFrames = if (maxConfidence >= INSTANT_CONFIDENCE_THRESHOLD) 1 else REQUIRED_CONSECUTIVE_FRAMES
        if (consecutiveHits < requiredFrames) {
            return null
        }

        val lastSeen = lastAnnouncedMs[best.category]
        if (lastSeen != null && (nowMillis - lastSeen) < DUPLICATE_SUPPRESSION_MS) {
            return null
        }

        lastAnnouncedMs[best.category] = nowMillis
        // In Kenya (left-hand traffic), signs on the left/center corridor (x <= 0.72) are more likely
        // applicable to the driver's carriageway, though monocular position alone remains advisory.
        val likelyDriverSide = if (best.normalizedCenterX in 0.15f..0.75f) null else false

        return TrafficSignObservation(
            id = UUID.randomUUID().toString(),
            category = best.category,
            confidence = maxConfidence,
            consecutiveFrames = consecutiveHits,
            applicableToDriverLane = likelyDriverSide,
            timestampMillis = nowMillis,
            modelVersion = modelVersion,
            customLabel = best.customLabel,
            isUncertain = maxConfidence < SURE_CONFIDENCE_THRESHOLD,
        )
    }

    fun reset() {
        candidateCategory = null
        consecutiveHits = 0
        missedFrames = 0
        maxConfidence = 0f
        lastAnnouncedMs.clear()
    }

    companion object {
        const val MIN_CONFIDENCE = 0.45f
        const val SURE_CONFIDENCE_THRESHOLD = 0.60f
        const val INSTANT_CONFIDENCE_THRESHOLD = 0.85f
        const val REQUIRED_CONSECUTIVE_FRAMES = 2
        const val DUPLICATE_SUPPRESSION_MS = 20_000L
    }
}
