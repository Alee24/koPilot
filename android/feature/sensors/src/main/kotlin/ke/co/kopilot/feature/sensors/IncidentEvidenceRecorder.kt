package ke.co.kopilot.feature.sensors

import java.io.File
import java.util.ArrayDeque

data class IncidentEvidenceSnapshot(
    val incidentId: String,
    val eventCategory: String,
    val triggeredAtMillis: Long,
    val preEventSecondsCaptured: Int,
    val postEventSecondsTarget: Int,
    val sampleCount: Int,
    val videoBufferAvailable: Boolean,
    val evidenceFilePath: String?,
    val limitationNote: String,
)

/**
 * Maintains a configurable rolling telemetry & camera-reference evidence buffer
 * targeting 15 seconds pre-event and 15 seconds post-event.
 *
 * When continuous hardware video ring-buffering is unavailable, saves the synchronized
 * sensor/GPS/OBD/watch evidence manifest locally and explicitly documents the limitation.
 */
class IncidentEvidenceRecorder(
    private val outputDirectory: File? = null,
) {
    private val rollingBuffer = ArrayDeque<SensorFrame>()
    private var activeCapture: PendingCapture? = null
    private val completedSnapshots = mutableListOf<IncidentEvidenceSnapshot>()

    private data class PendingCapture(
        val incidentId: String,
        val eventCategory: String,
        val triggeredAtMillis: Long,
        val triggeredMonotonicMs: Long,
        val postWindowMs: Long,
        val videoBufferAvailable: Boolean,
        val frames: MutableList<SensorFrame>,
    )

    fun recordFrame(frame: SensorFrame, preBufferSec: Int = 15) {
        rollingBuffer.addLast(frame)
        val preWindowMs = preBufferSec.coerceIn(5, 30) * 1_000L
        val cutoff = frame.monotonicMillis - preWindowMs
        while (rollingBuffer.isNotEmpty() && rollingBuffer.first.monotonicMillis < cutoff) {
            rollingBuffer.removeFirst()
        }

        val pending = activeCapture ?: return
        pending.frames.add(frame)
        if ((frame.monotonicMillis - pending.triggeredMonotonicMs) >= pending.postWindowMs) {
            finalizeCapture(pending)
            activeCapture = null
        }
    }

    fun startEventCapture(
        incidentId: String,
        eventCategory: String,
        nowMillis: Long,
        nowMonotonicMs: Long,
        preBufferSec: Int = 15,
        postBufferSec: Int = 15,
        videoBufferAvailable: Boolean = false,
    ): IncidentEvidenceSnapshot {
        val preFrames = rollingBuffer.toMutableList()
        val preSec = if (preFrames.size >= 2) {
            ((preFrames.last().monotonicMillis - preFrames.first().monotonicMillis) / 1000L).toInt()
                .coerceIn(0, preBufferSec)
        } else {
            0
        }
        val capture = PendingCapture(
            incidentId = incidentId,
            eventCategory = eventCategory,
            triggeredAtMillis = nowMillis,
            triggeredMonotonicMs = nowMonotonicMs,
            postWindowMs = postBufferSec.coerceIn(5, 30) * 1_000L,
            videoBufferAvailable = videoBufferAvailable,
            frames = preFrames,
        )
        activeCapture = capture

        // Immediately write initial pre-event snapshot so evidence is preserved even if power/app drops
        val savedPath = writeManifest(capture, finalized = false)
        val limitation = if (videoBufferAvailable) {
            "Camera rolling clip + synchronized telemetry captured."
        } else {
            "Rolling video buffer unavailable on current session; saved synchronized sensor/GPS/OBD telemetry (${preSec}s pre-event + ${postBufferSec}s post-event window)."
        }
        return IncidentEvidenceSnapshot(
            incidentId = incidentId,
            eventCategory = eventCategory,
            triggeredAtMillis = nowMillis,
            preEventSecondsCaptured = preSec,
            postEventSecondsTarget = postBufferSec,
            sampleCount = preFrames.size,
            videoBufferAvailable = videoBufferAvailable,
            evidenceFilePath = savedPath,
            limitationNote = limitation,
        )
    }

    fun getRecentSnapshots(): List<IncidentEvidenceSnapshot> = completedSnapshots.takeLast(20)

    private fun finalizeCapture(capture: PendingCapture) {
        val path = writeManifest(capture, finalized = true)
        val spanSec = if (capture.frames.size >= 2) {
            ((capture.frames.last().monotonicMillis - capture.frames.first().monotonicMillis) / 1000L).toInt()
        } else {
            0
        }
        completedSnapshots.add(
            IncidentEvidenceSnapshot(
                incidentId = capture.incidentId,
                eventCategory = capture.eventCategory,
                triggeredAtMillis = capture.triggeredAtMillis,
                preEventSecondsCaptured = spanSec / 2,
                postEventSecondsTarget = (capture.postWindowMs / 1000L).toInt(),
                sampleCount = capture.frames.size,
                videoBufferAvailable = capture.videoBufferAvailable,
                evidenceFilePath = path,
                limitationNote = if (capture.videoBufferAvailable) {
                    "Pre/post camera evidence and telemetry finalized."
                } else {
                    "Video ring-buffer unavailable; finalized ${capture.frames.size} synchronized sensor/GPS/OBD frames."
                },
            ),
        )
    }

    private fun writeManifest(capture: PendingCapture, finalized: Boolean): String? {
        val dir = outputDirectory ?: return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "safety_evidence_${capture.incidentId}.txt")
            val sb = StringBuilder()
            sb.appendLine("KOPILOT SAFETY EVIDENCE MANIFEST")
            sb.appendLine("incident_id=${capture.incidentId}")
            sb.appendLine("category=${capture.eventCategory}")
            sb.appendLine("triggered_at_ms=${capture.triggeredAtMillis}")
            sb.appendLine("finalized=$finalized")
            sb.appendLine("video_buffer_available=${capture.videoBufferAvailable}")
            sb.appendLine("frame_count=${capture.frames.size}")
            sb.appendLine("monotonic_ms,fwd_mps2,lat_mps2,vert_mps2,lin_mag_mps2,gyro_dps,pitch_deg,roll_deg,gps_kmh,obd_kmh,obd_rpm,hr_bpm")
            capture.frames.forEach { f ->
                sb.appendLine(
                    "${f.monotonicMillis},${"%.2f".format(f.forwardAccelMps2)},${"%.2f".format(f.lateralAccelMps2)}," +
                        "${"%.2f".format(f.verticalAccelMps2)},${"%.2f".format(f.linearMagMps2)}," +
                        "${"%.1f".format(f.angularVelDegPerSec)},${"%.1f".format(f.pitchDeltaDeg)}," +
                        "${"%.1f".format(f.rollDeltaDeg)},${f.gpsSpeedKmh ?: ""},${f.obdSpeedKmh ?: ""}," +
                        "${f.obdRpm ?: ""},${f.watchHeartRateBpm ?: ""}",
                )
            }
            file.writeText(sb.toString())
            file.absolutePath
        }.getOrNull()
    }
}
