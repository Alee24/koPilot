package ke.co.kopilot.feature.trips

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure formatting helpers for per-trip `.txt` activity logs so formatting is
 * deterministic and unit-testable on the JVM.
 */
object TripActivityFormatter {
    private val headerDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val lineTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun buildFileName(startedAtMillis: Long): String {
        val stamp = synchronized(fileStampFmt) { fileStampFmt.format(Date(startedAtMillis)) }
        return "trip_${startedAtMillis}_$stamp.txt"
    }

    fun buildHeader(startedAtMillis: Long, fileName: String): String {
        val startedStr = synchronized(headerDateFmt) { headerDateFmt.format(Date(startedAtMillis)) }
        return buildString {
            appendLine("=== KoPilot Trip Activity Log ===")
            appendLine("Started: $startedStr")
            appendLine("File: $fileName")
            appendLine("----------------------------------------")
        }
    }

    fun formatLine(type: String, details: String, timestampMillis: Long): String {
        val ts = synchronized(lineTimeFmt) { lineTimeFmt.format(Date(timestampMillis)) }
        val cleanDetails = details.replace('\n', ' ').replace('\r', ' ').trim()
        return "[$ts] [$type] $cleanDetails"
    }
}

/**
 * Writes a timestamped `.txt` activity log file for every driving trip, flushing each
 * activity line immediately to disk as it happens.
 */
@Singleton
class TripActivityLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var activeFile: File? = null
    private var activeStartedAtMillis: Long? = null

    private val _activeLogPath = MutableStateFlow<String?>(null)
    val activeLogPath: StateFlow<String?> = _activeLogPath.asStateFlow()

    private val _activeLineCount = MutableStateFlow(0)
    val activeLineCount: StateFlow<Int> = _activeLineCount.asStateFlow()

    private fun logsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "trip_logs").apply { if (!exists()) mkdirs() }
    }

    @Synchronized
    fun startTripLog(
        startedAtMillis: Long,
        originLabel: String?,
        destinationLabel: String?,
        latitude: Double?,
        longitude: Double?,
    ): File {
        val fileName = TripActivityFormatter.buildFileName(startedAtMillis)
        val file = File(logsDir(), fileName)
        activeFile = file
        activeStartedAtMillis = startedAtMillis
        _activeLogPath.value = file.absolutePath
        _activeLineCount.value = 0

        runCatching {
            FileWriter(file, false).use { writer ->
                writer.write(TripActivityFormatter.buildHeader(startedAtMillis, fileName))
                val coords = if (latitude != null && longitude != null) {
                    "GPS=${"%.5f".format(Locale.US, latitude)},${"%.5f".format(Locale.US, longitude)}"
                } else {
                    "GPS=acquiring"
                }
                val origin = originLabel?.takeIf { it.isNotBlank() } ?: "Current Location"
                val dest = destinationLabel?.takeIf { it.isNotBlank() } ?: "Free Drive"
                val startLine = TripActivityFormatter.formatLine(
                    type = "TRIP_START",
                    details = "Trip started | Origin: $origin | Destination: $dest | $coords",
                    timestampMillis = startedAtMillis,
                )
                writer.write(startLine)
                writer.write("\n")
                writer.flush()
                _activeLineCount.value = 1
            }
        }
        return file
    }

    @Synchronized
    fun logActivity(
        type: String,
        details: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val file = activeFile ?: return
        runCatching {
            FileWriter(file, true).use { writer ->
                writer.write(TripActivityFormatter.formatLine(type, details, timestampMillis))
                writer.write("\n")
                writer.flush()
            }
            _activeLineCount.value = _activeLineCount.value + 1
        }
    }

    @Synchronized
    fun endTripLog(
        endedAtMillis: Long,
        distanceKm: Double,
        maxSpeedKmh: Float,
        avgSpeedKmh: Float,
        signsDetectedCount: Int,
        alertsTriggeredCount: Int,
    ): File? {
        val file = activeFile
        val started = activeStartedAtMillis
        if (file != null) {
            val durationSec = if (started != null) ((endedAtMillis - started) / 1000L).coerceAtLeast(0L) else 0L
            val mins = durationSec / 60L
            val secs = durationSec % 60L
            val summary = "Trip ended | Duration: ${mins}m ${secs}s | Distance: ${"%.2f".format(Locale.US, distanceKm)} km | " +
                "Max Speed: ${"%.1f".format(Locale.US, maxSpeedKmh)} km/h | Avg Speed: ${"%.1f".format(Locale.US, avgSpeedKmh)} km/h | " +
                "Signs: $signsDetectedCount | Alerts: $alertsTriggeredCount"
            runCatching {
                FileWriter(file, true).use { writer ->
                    writer.write(TripActivityFormatter.formatLine("TRIP_END", summary, endedAtMillis))
                    writer.write("\n")
                    writer.flush()
                }
                _activeLineCount.value = _activeLineCount.value + 1
            }
        }
        activeFile = null
        activeStartedAtMillis = null
        _activeLogPath.value = null
        return file
    }

    fun findLogFileForTrip(startedAtMillis: Long): File? {
        val prefix = "trip_${startedAtMillis}_"
        return logsDir()
            .listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".txt") }
            ?.firstOrNull()
    }

    fun getActiveLogFile(): File? = activeFile

    fun readLogText(startedAtMillis: Long): String? {
        val file = findLogFileForTrip(startedAtMillis) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }
}
