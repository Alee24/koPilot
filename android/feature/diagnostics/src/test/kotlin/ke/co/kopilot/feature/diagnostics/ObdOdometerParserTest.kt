package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdOdometerParserTest {

    @Test
    fun `parses SAE J1979 Mode 01 PID A6 odometer in 0_1 km increments`() {
        // 125,400.0 km = 1,254,000 * 0.1 km = 0x00132270
        val km = ObdPidParser.parseOdometerKm("41 A6 00 13 22 70")
        assertEquals(125_400.0, km!!, 0.05)
    }

    @Test
    fun `returns null when vehicle or adapter does not support PID A6`() {
        assertNull(ObdPidParser.parseOdometerKm("NO DATA"))
        assertNull(ObdPidParser.parseOdometerKm("7F 01 12"))
    }
}
