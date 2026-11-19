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

/**
 * Multi-sign temporal confirmation tracker.
 * Tracks multiple sign categories concurrently across frames with a 1-frame motion-blur grace window,
 * keeping the full camera view (0.0..1.0) available without left/right exclusion zones.
 */
@Singleton
class SignTemporalTracker @Inject constructor() {

    private data class CategoryTrack(
        var consecutiveHits: Int = 0,
        var missedFrames: Int = 0,
        var maxConfidence: Float = 0f,
        var lastCenterX: Float = 0.5f,
        var customLabel: String? = null,
    )

    private val activeTracks = LinkedHashMap<TrafficSignCategory, CategoryTrack>()
    private val lastAnnouncedMs = mutableMapOf<TrafficSignCategory, Long>()

    @Synchronized
    fun processFrame(
        detections: List<RawSignDetection>,
        modelVersion: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): TrafficSignObservation? {
        val validByCategory = detections
            .filter { it.confidence >= MIN_CONFIDENCE }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.maxBy { it.confidence } }

        // 1. Update existing tracks and age out categories not seen in this frame
        val iterator = activeTracks.entries.iterator()
        while (iterator.hasNext()) {
            val (cat, track) = iterator.next()
            val hit = validByCategory[cat]
            if (hit != null) {
                track.consecutiveHits += 1
                track.missedFrames = 0
                track.maxConfidence = maxOf(track.maxConfidence, hit.confidence)
                track.lastCenterX = hit.normalizedCenterX
                if (hit.customLabel != null) track.customLabel = hit.customLabel
            } else {
                track.missedFrames += 1
                if (track.missedFrames > 1) {
                    iterator.remove()
                }
            }
        }

        // 2. Register newly observed categories in this frame
        for ((cat, hit) in validByCategory) {
            if (!activeTracks.containsKey(cat)) {
                activeTracks[cat] = CategoryTrack(
                    consecutiveHits = 1,
                    missedFrames = 0,
                    maxConfidence = hit.confidence,
                    lastCenterX = hit.normalizedCenterX,
                    customLabel = hit.customLabel,
                )
            }
        }

        // 3. Select the highest-priority confirmed sign that was observed in the current frame
        val confirmedCandidates = validByCategory.keys.mapNotNull { cat ->
            val track = activeTracks[cat] ?: return@mapNotNull null
            val requiredFrames = if (track.maxConfidence >= INSTANT_CONFIDENCE_THRESHOLD) 1 else REQUIRED_CONSECUTIVE_FRAMES
            if (track.consecutiveHits < requiredFrames) return@mapNotNull null
            val lastSeen = lastAnnouncedMs[cat]
            if (lastSeen != null && (nowMillis - lastSeen) < DUPLICATE_SUPPRESSION_MS) return@mapNotNull null
            cat to track
        }

        val chosen = confirmedCandidates
            .sortedWith(
                compareByDescending<Pair<TrafficSignCategory, CategoryTrack>> { (cat, _) -> cat.speedLimitKmh != null }
                    .thenByDescending { (_, track) -> track.maxConfidence },
            )
            .firstOrNull() ?: return null

        val (bestCategory, bestTrack) = chosen
        lastAnnouncedMs[bestCategory] = nowMillis

        return TrafficSignObservation(
            id = UUID.randomUUID().toString(),
            category = bestCategory,
            confidence = bestTrack.maxConfidence,
            consecutiveFrames = bestTrack.consecutiveHits,
            applicableToDriverLane = true,
            timestampMillis = nowMillis,
            modelVersion = modelVersion,
            customLabel = bestTrack.customLabel,
            isUncertain = bestTrack.maxConfidence < SURE_CONFIDENCE_THRESHOLD,
        )
    }

    @Synchronized
    fun reset() {
        activeTracks.clear()
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
