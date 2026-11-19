package ke.co.kopilot.safety

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import ke.co.kopilot.feature.sensors.AccidentAssessment
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String,
    val isPrimary: Boolean = false,
)

data class EmergencyNotificationRecord(
    val id: String,
    val timestampMillis: Long,
    val eventLabel: String,
    val confidencePercent: Int,
    val status: String,
    val recipients: String,
    val detail: String,
)

object EmergencySmsManager {
    private val sentIncidentIds = mutableSetOf<String>()

    fun parseContacts(rawJson: String): List<EmergencyContact> =
        runCatching {
            if (rawJson.isBlank()) return emptyList()
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    val name = o.optString("name", "")
                    val phone = o.optString("phone", "")
                    val isPrimary = o.optBoolean("isPrimary", false)
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        add(EmergencyContact(id.ifBlank { "c_$i" }, name, phone, isPrimary))
                    }
                }
            }
        }.getOrDefault(emptyList())

    fun encodeContacts(list: List<EmergencyContact>): String =
        runCatching {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("phone", c.phone)
                        put("isPrimary", c.isPrimary)
                    },
                )
            }
            arr.toString()
        }.getOrDefault("[]")

    fun parseHistory(rawJson: String): List<EmergencyNotificationRecord> =
        runCatching {
            if (rawJson.isBlank()) return emptyList()
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        EmergencyNotificationRecord(
                            id = o.optString("id", "h_$i"),
                            timestampMillis = o.optLong("timestampMillis", 0L),
                            eventLabel = o.optString("eventLabel", "Alert"),
                            confidencePercent = o.optInt("confidencePercent", 0),
                            status = o.optString("status", ""),
                            recipients = o.optString("recipients", ""),
                            detail = o.optString("detail", ""),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

    fun encodeHistory(list: List<EmergencyNotificationRecord>): String =
        runCatching {
            val arr = JSONArray()
            list.take(40).forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("timestampMillis", r.timestampMillis)
                        put("eventLabel", r.eventLabel)
                        put("confidencePercent", r.confidencePercent)
                        put("status", r.status)
                        put("recipients", r.recipients)
                        put("detail", r.detail)
                    },
                )
            }
            arr.toString()
        }.getOrDefault("[]")

    /**
     * Normalizes Kenyan local mobile formats (e.g. 0712345678 -> +254712345678, 0110... -> +254110...)
     * while preserving valid international numbers (+...).
     */
    fun formatPhoneNumber(raw: String): String {
        val cleaned = raw.replace(Regex("[\\s\\-()]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.startsWith("254") && cleaned.length >= 12 -> "+$cleaned"
            (cleaned.startsWith("07") || cleaned.startsWith("01")) && cleaned.length == 10 ->
                "+254${cleaned.drop(1)}"
            else -> cleaned
        }
    }

    fun buildEmergencyMessage(
        assessment: AccidentAssessment,
        latitude: Double?,
        longitude: Double?,
        isTest: Boolean = false,
    ): String {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(assessment.timestampMillis))
        val speedStr = assessment.preEventSpeedKmh?.let { "${it.toInt()} km/h" } ?: "unavailable"
        val locStr = if (latitude != null && longitude != null) {
            val accStr = assessment.gpsAccuracyMeters?.let { " (±${it.toInt()}m)" } ?: ""
            "https://www.google.com/maps?q=${"%.6f".format(Locale.US, latitude)},${"%.6f".format(Locale.US, longitude)}$accStr"
        } else {
            "GPS location unavailable"
        }
        val prefix = if (isTest) "KoPilot TEST Safety Alert" else "KoPilot Safety Alert"
        return "$prefix: A possible vehicle accident (${assessment.eventClass.label.lowercase()}, ${assessment.confidencePercent}% confidence) was detected at $timeStr. " +
            "Last recorded speed: $speedStr. Location: $locStr. Please contact the driver and check their safety."
    }

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    data class SmsDispatchResult(
        val success: Boolean,
        val statusLabel: String,
        val recipientCount: Int,
        val usedComposerFallback: Boolean = false,
    )

    fun sendEmergencySms(
        context: Context,
        incidentId: String,
        contacts: List<EmergencyContact>,
        message: String,
        autoSmsOptedIn: Boolean,
    ): SmsDispatchResult {
        if (contacts.isEmpty()) {
            return SmsDispatchResult(false, "No emergency contacts configured", 0)
        }
        if (!autoSmsOptedIn) {
            return SmsDispatchResult(false, "Automatic SMS is disabled in Safety Settings", 0)
        }
        if (sentIncidentIds.contains(incidentId)) {
            return SmsDispatchResult(true, "Duplicate suppressed (already sent for incident)", contacts.size)
        }

        val ordered = contacts.sortedByDescending { it.isPrimary }.map { formatPhoneNumber(it.phone) }.filter { it.isNotBlank() }
        if (ordered.isEmpty()) {
            return SmsDispatchResult(false, "No valid phone numbers", 0)
        }

        if (!hasSmsPermission(context)) {
            val launched = openSmsComposerFallback(context, ordered.first(), message)
            return SmsDispatchResult(
                success = launched,
                statusLabel = if (launched) "Opened SMS composer (SEND_SMS permission not granted)" else "SEND_SMS permission required",
                recipientCount = 1,
                usedComposerFallback = true,
            )
        }

        return runCatching {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            var sentCount = 0
            for (phone in ordered) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                sentCount++
            }
            sentIncidentIds.add(incidentId)
            SmsDispatchResult(true, "Sent to $sentCount contact(s)", sentCount)
        }.getOrElse { err ->
            val launched = openSmsComposerFallback(context, ordered.first(), message)
            SmsDispatchResult(
                success = false,
                statusLabel = "SMS failed (${err.localizedMessage ?: "no cellular/SIM"}); ${if (launched) "opened SMS app" else "retry manually"}",
                recipientCount = 0,
                usedComposerFallback = launched,
            )
        }
    }

    fun openSmsComposerFallback(context: Context, phone: String, message: String): Boolean =
        runCatching {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
