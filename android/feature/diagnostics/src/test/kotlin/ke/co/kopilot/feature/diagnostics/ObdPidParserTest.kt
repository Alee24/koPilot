package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidParserTest {
    @Test
    fun `parses rpm coolant speed and dtc codes accurately`() {
        assertEquals(1750, ObdPidParser.parseRpm("41 0C 1B 58 >"))
        assertEquals(1750, ObdPidParser.parseRpm("410C1B58"))
        assertEquals(90, ObdPidParser.parseCoolantTempC("41 05 82 >"))
        assertEquals(64, ObdPidParser.parseVehicleSpeedKmh("41 0D 40 >"))
        assertEquals(listOf("P0300", "P0420"), ObdPidParser.parseDtcCodes("43 03 00 04 20 00 00 >"))
    }

    @Test
    fun `decodes Mode 01 PID support bitmap and extended engine PIDs`() {
        val supported = ObdPidParser.parseSupportedPidsBitmap("41 00 BE 3E B8 11", 0x00)
        assertTrue("0104" in supported)
        assertTrue("0105" in supported)
        assertTrue("010C" in supported)
        assertTrue("010D" in supported)
        assertTrue("0111" in supported)
        assertFalse("0102" in supported)

        assertEquals(50.2f, ObdPidParser.parseEngineLoadPct("410480") ?: 0f, 0.2f)
        assertEquals(25, ObdPidParser.parseIntakeAirTempC("410F41"))
        assertEquals(14.1f, ObdPidParser.parseAdapterVoltage("14.1V") ?: 0f, 0.05f)
        assertEquals(13.85f, ObdPidParser.parseControlModuleVoltage("4142361A") ?: 0f, 0.05f)
    }

    @Test
    fun `detects adapter errors, filters transmission DTCs, and identifies HHOBD name hints`() {
        assertTrue(ObdPidParser.isAdapterError("NO DATA"))
        assertTrue(ObdPidParser.isAdapterError("UNABLE TO CONNECT"))
        assertNull(ObdPidParser.parseRpm("NO DATA"))

        val transDtcs = ObdPidParser.filterTransmissionDtcs(listOf("P0300", "P0700", "P0841", "P0420"))
        assertEquals(listOf("P0700", "P0841"), transDtcs)

        assertTrue(ObdConnectionManager.isHhobdNameHint("HHOBD Advanced"))
        assertTrue(ObdConnectionManager.isHhobdNameHint("OBDII"))
        assertFalse(ObdConnectionManager.isHhobdNameHint("JBL Flip 6"))
    }
}
