package ke.co.kopilot.feature.trips

import org.junit.Assert.assertTrue
import org.junit.Test

class TripActivityFormatterTest {

    @Test
    fun `buildFileName and buildHeader format trip log metadata cleanly`() {
        val startedAt = 1_763_355_912_000L
        val fileName = TripActivityFormatter.buildFileName(startedAt)
        assertTrue(fileName.startsWith("trip_${startedAt}_"))
        assertTrue(fileName.endsWith(".txt"))

        val header = TripActivityFormatter.buildHeader(startedAt, fileName)
        assertTrue(header.contains("KoPilot Trip Activity Log"))
        assertTrue(header.contains(fileName))
    }

    @Test
    fun `formatLine flattens newlines into single activity line`() {
        val line = TripActivityFormatter.formatLine(
            type = "SIGN_DETECTED",
            details = "50 km/h Speed Limit\n(92% confirmed)",
            timestampMillis = 1_763_355_912_000L,
        )
        assertTrue(line.contains("[SIGN_DETECTED]"))
        assertTrue(line.contains("50 km/h Speed Limit (92% confirmed)"))
        assertTrue(!line.contains("\n"))
    }
}
