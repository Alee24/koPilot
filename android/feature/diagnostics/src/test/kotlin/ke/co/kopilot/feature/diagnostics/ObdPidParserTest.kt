package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ObdPidParserTest {
    @Test
    fun `parses rpm coolant speed and dtc codes accurately`() {
        assertEquals(1750, ObdPidParser.parseRpm("41 0C 1B 58 >"))
        assertEquals(90, ObdPidParser.parseCoolantTempC("41 05 82 >"))
        assertEquals(64, ObdPidParser.parseVehicleSpeedKmh("41 0D 40 >"))
        assertEquals(listOf("P0300", "P0420"), ObdPidParser.parseDtcCodes("43 03 00 04 20 00 00 >"))
    }
}
