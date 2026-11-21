package ke.co.kopilot.core.database.maintenance

import java.util.concurrent.TimeUnit

enum class OdometerSource(val label: String) {
    ECU("Vehicle reported (OBD)"),
    MANUAL("Manual entry"),
    UNSET("Not set"),
}

enum class MaintenanceCategory(
    val displayName: String,
    val defaultIntervalKm: Int,
    val defaultIntervalMonths: Int,
) {
    ENGINE_OIL("Engine Oil & Filter", 5_000, 6),
    AIR_FILTER("Air Filter", 20_000, 12),
    SPARK_PLUGS("Spark Plugs", 40_000, 24),
    CVT_TRANSMISSION_FLUID("CVT / Transmission Fluid", 60_000, 36),
    BRAKE_INSPECTION("Brake Inspection", 15_000, 12),
    COOLANT("Coolant", 40_000, 24),
    TYRES("Tyres", 40_000, 36),
    BATTERY("Battery", 50_000, 24),
    CUSTOM("Custom Maintenance", 10_000, 12),
}

enum class ServiceUrgency(val label: String) {
    OK("OK"),
    REMINDER_1000("Due within 1,000 km"),
    REMINDER_500("Due within 500 km"),
    REMINDER_100("Due within 100 km"),
    DUE_SOON("Due Soon"),
    OVERDUE("Overdue"),
    UNCONFIGURED("Set Odometer"),
}

data class MaintenanceScheduleItem(
    val id: String,
    val category: MaintenanceCategory,
    val customTitle: String = "",
    val lastServiceOdometerKm: Double,
    val lastServiceDateMillis: Long,
    val intervalKm: Int,
    val intervalMonths: Int,
) {
    val title: String
        get() = if (category == MaintenanceCategory.CUSTOM && customTitle.isNotBlank()) customTitle else category.displayName

    val nextServiceOdometerKm: Double
        get() = lastServiceOdometerKm + intervalKm

    val nextServiceDateMillis: Long
        get() = lastServiceDateMillis + TimeUnit.DAYS.toMillis(intervalMonths.toLong() * 30L)
}

data class ServiceRecord(
    val id: String,
    val category: MaintenanceCategory,
    val title: String,
    val serviceDateMillis: Long,
    val odometerKm: Double,
    val costKes: Double? = null,
    val notes: String = "",
)

data class EvaluatedServiceItem(
    val schedule: MaintenanceScheduleItem,
    val kmRemaining: Double?,
    val daysRemaining: Long,
    val progressFraction: Float,
    val urgency: ServiceUrgency,
)

object MaintenanceCalculator {

    fun defaultSchedules(baseOdometerKm: Double = 0.0, nowMillis: Long = System.currentTimeMillis()): List<MaintenanceScheduleItem> =
        MaintenanceCategory.entries.filter { it != MaintenanceCategory.CUSTOM }.map { cat ->
            MaintenanceScheduleItem(
                id = "sched_${cat.name.lowercase()}",
                category = cat,
                lastServiceOdometerKm = baseOdometerKm,
                lastServiceDateMillis = nowMillis,
                intervalKm = cat.defaultIntervalKm,
                intervalMonths = cat.defaultIntervalMonths,
            )
        }

    fun evaluate(
        schedules: List<MaintenanceScheduleItem>,
        currentOdometerKm: Double?,
        nowMillis: Long,
        thresholdsKm: List<Int> = listOf(1000, 500, 100),
    ): List<EvaluatedServiceItem> {
        val sortedThresholds = thresholdsKm.sortedDescending()
        val t1000 = sortedThresholds.getOrElse(0) { 1000 }.toDouble()
        val t500 = sortedThresholds.getOrElse(1) { 500 }.toDouble()
        val t100 = sortedThresholds.getOrElse(2) { 100 }.toDouble()

        return schedules.map { item ->
            val kmRem = currentOdometerKm?.let { item.nextServiceOdometerKm - it }
            val daysRem = TimeUnit.MILLISECONDS.toDays(item.nextServiceDateMillis - nowMillis)
            val progress = if (currentOdometerKm != null && item.intervalKm > 0) {
                ((currentOdometerKm - item.lastServiceOdometerKm) / item.intervalKm.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

            val urgency = when {
                kmRem == null && daysRem > 30L -> ServiceUrgency.UNCONFIGURED
                (kmRem != null && kmRem < 0.0) || daysRem < 0L -> ServiceUrgency.OVERDUE
                (kmRem != null && kmRem <= 0.0) || daysRem == 0L -> ServiceUrgency.DUE_SOON
                (kmRem != null && kmRem <= t100) || daysRem <= 7L -> ServiceUrgency.REMINDER_100
                (kmRem != null && kmRem <= t500) || daysRem <= 14L -> ServiceUrgency.REMINDER_500
                (kmRem != null && kmRem <= t1000) || daysRem <= 30L -> ServiceUrgency.REMINDER_1000
                else -> ServiceUrgency.OK
            }

            EvaluatedServiceItem(
                schedule = item,
                kmRemaining = kmRem,
                daysRemaining = daysRem,
                progressFraction = progress,
                urgency = urgency,
            )
        }.sortedBy { it.kmRemaining ?: Double.MAX_VALUE }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    private fun unesc(s: String): String = s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")

    fun encodeSchedules(list: List<MaintenanceScheduleItem>): String =
        list.joinToString("\n") { s ->
            listOf(
                esc(s.id),
                s.category.name,
                esc(s.customTitle),
                s.lastServiceOdometerKm.toString(),
                s.lastServiceDateMillis.toString(),
                s.intervalKm.toString(),
                s.intervalMonths.toString(),
            ).joinToString("|")
        }

    fun parseSchedules(raw: String): List<MaintenanceScheduleItem> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapIndexedNotNull { idx, line ->
            val parts = line.split("|")
            if (parts.size < 7) return@mapIndexedNotNull null
            val cat = runCatching { MaintenanceCategory.valueOf(parts[1]) }.getOrDefault(MaintenanceCategory.CUSTOM)
            MaintenanceScheduleItem(
                id = unesc(parts[0]).ifBlank { "sched_$idx" },
                category = cat,
                customTitle = unesc(parts[2]),
                lastServiceOdometerKm = parts[3].toDoubleOrNull() ?: 0.0,
                lastServiceDateMillis = parts[4].toLongOrNull() ?: System.currentTimeMillis(),
                intervalKm = parts[5].toIntOrNull() ?: cat.defaultIntervalKm,
                intervalMonths = parts[6].toIntOrNull() ?: cat.defaultIntervalMonths,
            )
        }.toList()
    }

    fun encodeServiceHistory(list: List<ServiceRecord>): String =
        list.joinToString("\n") { r ->
            listOf(
                esc(r.id),
                r.category.name,
                esc(r.title),
                r.serviceDateMillis.toString(),
                r.odometerKm.toString(),
                r.costKes?.toString().orEmpty(),
                esc(r.notes),
            ).joinToString("|")
        }

    fun parseServiceHistory(raw: String): List<ServiceRecord> {
        if (raw.isBlank() || raw.trim() == "[]") return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapIndexedNotNull { idx, line ->
            val parts = line.split("|")
            if (parts.size < 7) return@mapIndexedNotNull null
            val cat = runCatching { MaintenanceCategory.valueOf(parts[1]) }.getOrDefault(MaintenanceCategory.CUSTOM)
            ServiceRecord(
                id = unesc(parts[0]).ifBlank { "srv_$idx" },
                category = cat,
                title = unesc(parts[2]).ifBlank { cat.displayName },
                serviceDateMillis = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                odometerKm = parts[4].toDoubleOrNull() ?: 0.0,
                costKes = parts[5].toDoubleOrNull(),
                notes = unesc(parts[6]),
            )
        }.toList()
    }
}
