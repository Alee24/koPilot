package ke.co.kopilot.feature.vision

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class RawObjectDetection(
    val label: String,
    val confidence: Float,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
)

/**
 * Lightweight IoU-based multi-object tracker assigning persistent tracking IDs across
 * frames and tolerating brief 2-frame occlusions.
 */
@Singleton
class MultiObjectTracker @Inject constructor() {

    private data class TrackState(
        val id: Int,
        var latest: TrackedRoadObject,
        var missedFrames: Int = 0,
    )

    private var nextId = 1
    private val activeTracks = mutableListOf<TrackState>()

    @Synchronized
    fun update(
        detections: List<RawObjectDetection>,
        timestampMillis: Long,
    ): List<TrackedRoadObject> {
        val matchedTrackIndices = mutableSetOf<Int>()
        val results = mutableListOf<TrackedRoadObject>()

        for (det in detections) {
            var bestIdx = -1
            var bestIou = MIN_IOU_THRESHOLD

            activeTracks.forEachIndexed { idx, track ->
                if (idx in matchedTrackIndices) return@forEachIndexed
                if (track.latest.label != det.label) return@forEachIndexed
                val iou = computeIou(
                    det.normalizedLeft, det.normalizedTop, det.normalizedRight, det.normalizedBottom,
                    track.latest.normalizedLeft, track.latest.normalizedTop, track.latest.normalizedRight, track.latest.normalizedBottom,
                )
                if (iou > bestIou) {
                    bestIou = iou
                    bestIdx = idx
                }
            }

            val assignedId = if (bestIdx >= 0) {
                matchedTrackIndices.add(bestIdx)
                activeTracks[bestIdx].id
            } else {
                nextId++
            }

            val trackedObj = TrackedRoadObject(
                trackingId = assignedId,
                label = det.label,
                confidence = det.confidence,
                normalizedLeft = det.normalizedLeft,
                normalizedTop = det.normalizedTop,
                normalizedRight = det.normalizedRight,
                normalizedBottom = det.normalizedBottom,
                timestampMillis = timestampMillis,
            )

            if (bestIdx >= 0) {
                activeTracks[bestIdx].latest = trackedObj
                activeTracks[bestIdx].missedFrames = 0
            } else {
                activeTracks.add(TrackState(id = assignedId, latest = trackedObj, missedFrames = 0))
            }
            results.add(trackedObj)
        }

        val iterator = activeTracks.iterator()
        var idx = 0
        val toRemove = mutableListOf<TrackState>()
        for (track in activeTracks) {
            if (idx !in matchedTrackIndices && track.latest.timestampMillis != timestampMillis) {
                track.missedFrames += 1
                if (track.missedFrames > MAX_MISSED_FRAMES) {
                    toRemove.add(track)
                }
            }
            idx++
        }
        activeTracks.removeAll(toRemove)
        return results
    }

    @Synchronized
    fun reset() {
        activeTracks.clear()
        nextId = 1
    }

    companion object {
        const val MIN_IOU_THRESHOLD = 0.25f
        const val MAX_MISSED_FRAMES = 3

        fun computeIou(
            l1: Float, t1: Float, r1: Float, b1: Float,
            l2: Float, t2: Float, r2: Float, b2: Float,
        ): Float {
            val interL = max(l1, l2)
            val interT = max(t1, t2)
            val interR = min(r1, r2)
            val interB = min(b1, b2)
            val interW = (interR - interL).coerceAtLeast(0f)
            val interH = (interB - interT).coerceAtLeast(0f)
            val interArea = interW * interH
            if (interArea <= 0f) return 0f
            val area1 = (r1 - l1).coerceAtLeast(0f) * (b1 - t1).coerceAtLeast(0f)
            val area2 = (r2 - l2).coerceAtLeast(0f) * (b2 - t2).coerceAtLeast(0f)
            val union = (area1 + area2 - interArea).coerceAtLeast(1e-6f)
            return interArea / union
        }
    }
}
