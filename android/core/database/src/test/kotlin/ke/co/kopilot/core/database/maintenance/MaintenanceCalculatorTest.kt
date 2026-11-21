package ke.co.kopilot.core.database.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MaintenanceCalculatorTest {

    @Test
    fun `evaluates 1000km, 500km, 100km, due, and overdue service reminders accurately`() {
        val now = 1_700_000_000_000L
        val schedule = MaintenanceScheduleItem(
            id = "oil",
            category = MaintenanceCategory.ENGINE_OIL,
            lastServiceOdometerKm = 120_000.0,
            lastServiceDateMillis = now - TimeUnit.DAYS.toMillis(60),
            intervalKm = 5_000,
            intervalMonths = 6,
        )
        // Next service = 125,000 km
        assertEquals(125_000.0, schedule.nextServiceOdometerKm, 0.01)

        val ok = MaintenanceCalculator.evaluate(listOf(schedule), 123_000.0, now).first()
        assertEquals(ServiceUrgency.OK, ok.urgency)
        assertEquals(2_000.0, ok.kmRemaining!!, 0.01)

        val rem1000 = MaintenanceCalculator.evaluate(listOf(schedule), 124_100.0, now).first()
        assertEquals(ServiceUrgency.REMINDER_1000, rem1000.urgency)

        val rem500 = MaintenanceCalculator.evaluate(listOf(schedule), 124_600.0, now).first()
        assertEquals(ServiceUrgency.REMINDER_500, rem500.urgency)

        val rem100 = MaintenanceCalculator.evaluate(listOf(schedule), 124_920.0, now).first()
        assertEquals(ServiceUrgency.REMINDER_100, rem100.urgency)

        val due = MaintenanceCalculator.evaluate(listOf(schedule), 125_000.0, now).first()
        assertEquals(ServiceUrgency.DUE_SOON, due.urgency)

        val overdue = MaintenanceCalculator.evaluate(listOf(schedule), 125_350.0, now).first()
        assertEquals(ServiceUrgency.OVERDUE, overdue.urgency)
        assertEquals(-350.0, overdue.kmRemaining!!, 0.01)
    }

    @Test
    fun `triggers overdue when date limit is reached even if mileage limit is not`() {
        val now = 1_700_000_000_000L
        val schedule = MaintenanceScheduleItem(
            id = "cvt",
            category = MaintenanceCategory.CVT_TRANSMISSION_FLUID,
            lastServiceOdometerKm = 100_000.0,
            lastServiceDateMillis = now - TimeUnit.DAYS.toMillis(365 * 4L),
            intervalKm = 60_000,
            intervalMonths = 36,
        )
        val eval = MaintenanceCalculator.evaluate(listOf(schedule), 110_000.0, now).first()
        assertEquals(ServiceUrgency.OVERDUE, eval.urgency)
        assertTrue(eval.daysRemaining < 0L)
    }

    @Test
    fun `persists and parses maintenance schedules and service history cleanly`() {
        val now = 1_700_000_000_000L
        val defaults = MaintenanceCalculator.defaultSchedules(125_400.0, now)
        val encoded = MaintenanceCalculator.encodeSchedules(defaults)
        val decoded = MaintenanceCalculator.parseSchedules(encoded)
        assertEquals(defaults.size, decoded.size)
        assertEquals(125_400.0, decoded.first().lastServiceOdometerKm, 0.01)

        val hist = listOf(ServiceRecord("1", MaintenanceCategory.ENGINE_OIL, "Engine Oil & Filter", now, 125_400.0, 6500.0, "Synthetic 5W-30"))
        val decodedHist = MaintenanceCalculator.parseServiceHistory(MaintenanceCalculator.encodeServiceHistory(hist))
        assertEquals(1, decodedHist.size)
        assertEquals(6500.0, decodedHist[0].costKes!!, 0.01)
    }
}
