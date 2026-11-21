package ke.co.kopilot.feature.trips

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TripReportExporter {

    private fun reportsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "trip_reports").apply { if (!exists()) mkdirs() }
    }

    fun exportPdf(context: Context, report: TripIntelligenceReport): File {
        val outFile = File(reportsDir(context), "kopilot_report_${report.startedAtMillis}.pdf")
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(7, 16, 25)
            textSize = 18f
            isFakeBoldText = true
        }
        val headingPaint = Paint().apply {
            color = Color.rgb(0, 125, 105)
            textSize = 13f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 10.5f
        }

        var y = 44f
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(report.startedAtMillis))
        canvas.drawText("KoPilot Trip Intelligence Report", 36f, y, titlePaint)
        y += 20f
        canvas.drawText("${report.originLabel} -> ${report.destinationLabel} • $dateStr • ${report.statusLabel}", 36f, y, bodyPaint)
        y += 16f
        canvas.drawText("Vehicle: ${report.vehicleName} | Safety: ${report.accidentBannerTitle}", 36f, y, bodyPaint)
        y += 24f

        canvas.drawText("Summary Metrics", 36f, y, headingPaint)
        y += 16f
        canvas.drawText(
            "Distance: ${"%.2f".format(Locale.US, report.distanceKm)} km | Duration: ${report.durationSeconds / 60}m ${report.durationSeconds % 60}s | " +
                "Avg Moving: ${report.avgMovingSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable"} | Max: ${report.maxSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable"}",
            36f,
            y,
            bodyPaint,
        )
        y += 16f
        canvas.drawText(
            "Score: ${report.scoreBreakdown.overallScore?.let { "$it/100" } ?: "Unavailable"} | GPS Quality: ${report.gpsQualityLabel} | Completeness: ${report.dataCompletenessPercent}%",
            36f,
            y,
            bodyPaint,
        )
        y += 22f

        fun drawSection(title: String, metrics: List<MeasuredMetric<String>>) {
            if (y > 760f) return
            canvas.drawText(title, 36f, y, headingPaint)
            y += 15f
            for (m in metrics) {
                if (y > 790f) break
                canvas.drawText("• ${m.label}: ${m.formattedValue} [${m.provenance.label} • ${m.source}]", 42f, y, bodyPaint)
                y += 14f
            }
            y += 8f
        }

        drawSection("OBD-II Vehicle Diagnostics", report.obdMetrics)
        drawSection("Motion & Rough-Road Analytics", report.motionMetrics)
        drawSection("Smartwatch & Wellness", report.wellnessMetrics)
        drawSection("Weather & Device Performance", report.weatherMetrics + report.performanceMetrics)

        if (y < 770f) {
            canvas.drawText("Events (${report.events.size})", 36f, y, headingPaint)
            y += 15f
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (ev in report.events.take(10)) {
                if (y > 805f) break
                canvas.drawText("• [${timeFmt.format(Date(ev.timestampMillis))}] ${ev.title}: ${ev.description.take(85)}", 42f, y, bodyPaint)
                y += 13f
            }
        }

        doc.finishPage(page)
        FileOutputStream(outFile).use { fos -> doc.writeTo(fos) }
        doc.close()
        return outFile
    }

    fun exportCsv(context: Context, report: TripIntelligenceReport): File {
        val outFile = File(reportsDir(context), "kopilot_timeseries_${report.startedAtMillis}.csv")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("timestamp_iso,lat,lng,gps_speed_kmh,obd_speed_kmh,engine_rpm,coolant_c,maf_gps,voltage_v,heart_rate_bpm,altitude_m")
        for (s in report.samples) {
            sb.appendLine(
                listOf(
                    isoFmt.format(Date(s.timestampMillis)),
                    s.latitude?.toString().orEmpty(),
                    s.longitude?.toString().orEmpty(),
                    s.gpsSpeedKmh?.toString().orEmpty(),
                    s.obdSpeedKmh?.toString().orEmpty(),
                    s.engineRpm?.toString().orEmpty(),
                    s.coolantTempC?.toString().orEmpty(),
                    s.mafGramsPerSec?.toString().orEmpty(),
                    s.voltageV?.toString().orEmpty(),
                    s.heartRateBpm?.toString().orEmpty(),
                    s.altitudeMeters?.toString().orEmpty(),
                ).joinToString(","),
            )
        }
        outFile.writeText(sb.toString())
        return outFile
    }

    fun exportJson(context: Context, report: TripIntelligenceReport): File {
        val outFile = File(reportsDir(context), "kopilot_report_${report.startedAtMillis}.json")
        val root = JSONObject().apply {
            put("schemaVersion", "1.0")
            put("tripId", report.tripId)
            put("status", report.statusLabel)
            put("origin", report.originLabel)
            put("destination", report.destinationLabel)
            put("vehicle", report.vehicleName)
            put("startedAtMillis", report.startedAtMillis)
            put("endedAtMillis", report.endedAtMillis ?: JSONObject.NULL)
            put("durationSeconds", report.durationSeconds)
            put("distanceKm", report.distanceKm)
            put("dataCompletenessPercent", report.dataCompletenessPercent)
            put("accidentBanner", report.accidentBannerTitle)
            put("score", report.scoreBreakdown.overallScore ?: JSONObject.NULL)
            put(
                "samples",
                JSONArray().apply {
                    report.samples.forEach { s ->
                        put(
                            JSONObject().apply {
                                put("timestampMillis", s.timestampMillis)
                                put("lat", s.latitude ?: JSONObject.NULL)
                                put("lng", s.longitude ?: JSONObject.NULL)
                                put("gpsSpeedKmh", s.gpsSpeedKmh ?: JSONObject.NULL)
                                put("obdSpeedKmh", s.obdSpeedKmh ?: JSONObject.NULL)
                                put("rpm", s.engineRpm ?: JSONObject.NULL)
                                put("coolantC", s.coolantTempC ?: JSONObject.NULL)
                                put("mafGps", s.mafGramsPerSec ?: JSONObject.NULL)
                                put("voltageV", s.voltageV ?: JSONObject.NULL)
                                put("heartRateBpm", s.heartRateBpm ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            put(
                "events",
                JSONArray().apply {
                    report.events.forEach { e ->
                        put(
                            JSONObject().apply {
                                put("timestampMillis", e.timestampMillis)
                                put("category", e.category.name)
                                put("title", e.title)
                                put("description", e.description)
                                put("severity", e.severity)
                            },
                        )
                    }
                },
            )
        }
        outFile.writeText(root.toString(2))
        return outFile
    }

    fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, subject))
        }
    }
}
