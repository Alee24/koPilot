package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdReadOnlyFirewallAndCatalogTest {

    @Test
    fun firewall_allowsReviewedReadCommandsAndBlocksWritesOrClears() {
        assertTrue(ObdReadOnlyFirewall.isAllowed("ATZ"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("010C"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("03"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("07"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("0A"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("020000"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("0902"))

        // Prohibited commands
        assertFalse("Mode 04 clear DTCs must be blocked", ObdReadOnlyFirewall.isAllowed("04"))
        assertFalse("ECU reset must be blocked", ObdReadOnlyFirewall.isAllowed("1101"))
        assertFalse("Security access must be blocked", ObdReadOnlyFirewall.isAllowed("2701"))
        assertFalse("Write data by ID must be blocked", ObdReadOnlyFirewall.isAllowed("2EF190"))
        assertFalse("Routine control must be blocked", ObdReadOnlyFirewall.isAllowed("3101"))
        assertFalse("Flashing/download must be blocked", ObdReadOnlyFirewall.isAllowed("3400"))
        assertFalse("Arbitrary AT CAN header must be blocked", ObdReadOnlyFirewall.isAllowed("ATSH7E0"))
    }

    @Test
    fun pidParser_decodesExtendedJ1979PidsAndPendingPermanentDtcs() {
        assertEquals(60, ObdPidParser.parseEngineOilTempC("41 5C 64"))
        val fuelRate = ObdPidParser.parseEngineFuelRateLph("41 5E 00 C8")
        assertNotNull(fuelRate)
        assertEquals(10.0f, fuelRate!!, 0.01f)
        assertEquals(101, ObdPidParser.parseBarometricPressureKpa("41 33 65"))
        assertEquals(150, ObdPidParser.parseFuelPressureGaugeKpa("41 0A 32"))

        val pending = ObdPidParser.parseDtcCodes("47 01 71 03 00", expectedResponseMode = "47")
        assertEquals(listOf("P0171", "P0300"), pending)

        val permanent = ObdPidParser.parseDtcCodes("4A 04 20 00 00", expectedResponseMode = "4A")
        assertEquals(listOf("P0420"), permanent)
    }

    @Test
    fun pidCatalog_validatesRanges() {
        assertTrue(ObdPidCatalog.isWithinValidRange("0105", 90f))
        assertFalse(ObdPidCatalog.isWithinValidRange("0105", 300f))
        assertTrue(ObdPidCatalog.isWithinValidRange("010C", 2500f))
        assertFalse(ObdPidCatalog.isWithinValidRange("010C", -50f))
    }
}
