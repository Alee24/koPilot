package ke.co.kopilot.safety

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import ke.co.kopilot.feature.sensors.AccidentAssessment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private const val ACTION_SMS_SENT = "ke.co.kopilot.safety.ACTION_SMS_SENT"
    private val sentIncidentIds = mutableSetOf<String>()

    private val _lastCarrierStatus = MutableStateFlow<String?>(null)
    val lastCarrierStatus: StateFlow<String?> = _lastCarrierStatus.asStateFlow()

    fun clearSentIncidentCacheForTests() {
        sentIncidentIds.clear()
    }

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
        val impactStr = "${"%.1f".format(Locale.US, assessment.peakImpactG)}g"
        val locStr = if (latitude != null && longitude != null) {
            val accStr = assessment.gpsAccuracyMeters?.let { " (±${it.toInt()}m)" } ?: ""
            "Lat ${"%.5f".format(Locale.US, latitude)}, Lng ${"%.5f".format(Locale.US, longitude)}$accStr " +
                "https://www.google.com/maps?q=${"%.6f".format(Locale.US, latitude)},${"%.6f".format(Locale.US, longitude)}"
        } else {
            "GPS location unavailable"
        }
        val prefix = if (isTest || assessment.isSimulation) "KoPilot SIMULATION Safety Alert" else "KoPilot Safety Alert"
        return "$prefix: A POSSIBLE accident was detected (not yet confirmed) at $timeStr. " +
            "Motion peak: $impactStr, last speed: $speedStr. Location: $locStr. " +
            "Please contact the driver immediately to check their safety."
    }

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    data class SmsDispatchResult(
        val success: Boolean,
        val statusLabel: String,
        val recipientCount: Int,
        val usedComposerFallback: Boolean = false,
        val deliveryPendingConfirmation: Boolean = false,
    )

    fun sendEmergencySms(
        context: Context,
        incidentId: String,
        contacts: List<EmergencyContact>,
        message: String,
        autoSmsOptedIn: Boolean,
        isSimulation: Boolean = false,
    ): SmsDispatchResult {
        if (isSimulation) {
            return SmsDispatchResult(
                success = false,
                statusLabel = "Simulation mode — real SMS strictly blocked",
                recipientCount = 0,
            )
        }
        if (contacts.isEmpty()) {
            return SmsDispatchResult(false, "No emergency contacts configured", 0)
        }
        if (!autoSmsOptedIn) {
            return SmsDispatchResult(false, "Automatic SMS is disabled in Safety Settings", 0)
        }
        val safeIncidentId = incidentId.ifBlank { "inc_${System.currentTimeMillis() / 30_000L}" }
        if (sentIncidentIds.contains(safeIncidentId)) {
            return SmsDispatchResult(true, "Duplicate suppressed (already dispatched for incident $safeIncidentId)", contacts.size)
        }

        val ordered = contacts.sortedByDescending { it.isPrimary }.map { formatPhoneNumber(it.phone) }.filter { it.isNotBlank() }
        if (ordered.isEmpty()) {
            return SmsDispatchResult(false, "No valid phone numbers", 0)
        }

        if (!hasSmsPermission(context)) {
            val launched = openSmsComposerFallback(context, ordered.first(), message)
            return SmsDispatchResult(
                success = launched,
                statusLabel = if (launched) "Opened SMS composer (SEND_SMS permission not granted)" else "SEND_SMS permission required — use manual SOS",
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
            registerSentStatusReceiver(context.applicationContext, safeIncidentId)
            var queuedCount = 0
            for ((idx, phone) in ordered.withIndex()) {
                val parts = smsManager.divideMessage(message)
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (p in parts.indices) {
                    val sentIntent = Intent(ACTION_SMS_SENT).apply {
                        setPackage(context.packageName)
                        putExtra("incidentId", safeIncidentId)
                        putExtra("phone", phone)
                    }
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            context,
                            (safeIncidentId.hashCode() + idx * 17 + p) and 0x7FFFFFFF,
                            sentIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                queuedCount++
            }
            sentIncidentIds.add(safeIncidentId)
            _lastCarrierStatus.value = "Queued to carrier for $queuedCount contact(s) — awaiting carrier confirmation"
            SmsDispatchResult(
                success = true,
                statusLabel = "Queued to carrier for $queuedCount contact(s) (awaiting network confirmation)",
                recipientCount = queuedCount,
                deliveryPendingConfirmation = true,
            )
        }.getOrElse { err ->
            val launched = openSmsComposerFallback(context, ordered.first(), message)
            _lastCarrierStatus.value = "SMS dispatch failed (${err.localizedMessage ?: "no cellular/SIM"})"
            SmsDispatchResult(
                success = false,
                statusLabel = "SMS unavailable (${err.localizedMessage ?: "no cellular/SIM"}); ${if (launched) "opened SMS app" else "use manual SOS"}",
                recipientCount = 0,
                usedComposerFallback = launched,
            )
        }
    }

    private fun registerSentStatusReceiver(appContext: Context, incidentId: String) {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getStringExtra("incidentId") != incidentId) return
                    val resultText = when (resultCode) {
                        Activity.RESULT_OK -> "Carrier confirmed SMS sent"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS failed: No cellular service"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS failed: Airplane mode / radio off"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS failed: Carrier rejected or no credit/SIM"
                        else -> "SMS carrier error (code=$resultCode)"
                    }
                    _lastCarrierStatus.value = resultText
                    runCatching { appContext.unregisterReceiver(this) }
                }
            }
            val filter = IntentFilter(ACTION_SMS_SENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        }
    }

    fun openEmergencyDialerFallback(context: Context, phone: String = "999"): Boolean =
        runCatching {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

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
