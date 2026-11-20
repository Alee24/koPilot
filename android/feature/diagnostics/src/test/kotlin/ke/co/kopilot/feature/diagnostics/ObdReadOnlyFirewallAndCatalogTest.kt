package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ObdReadOnlyFirewallAndCatalogTest {

    @Test
    fun firewall_allowsReviewedReadCommandsAndBlocksWritesOrClears() {
        assertTrue(ObdReadOnlyFirewall.isAllowed("ATZ"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("ATWS"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("ATD"))
        assertTrue(ObdReadOnlyFirewall.isAllowed("ATSP4"))
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
    fun pidParser_handlesBusInitOkAndSearchingPrefixesWithoutFalseErrors() {
        val kwpResponse = "BUS INIT: ...OK\r41 00 BE 3E B8 11"
        assertFalse("BUS INIT: ...OK with 41 00 payload must not be treated as error", ObdPidParser.isAdapterError(kwpResponse))
        val supported = ObdPidParser.parseSupportedPidsBitmap(kwpResponse, 0x00)
        assertTrue("010C should be decoded from KWP BUS INIT OK bitmap", "010C" in supported)
        assertTrue("0105 should be decoded from KWP BUS INIT OK bitmap", "0105" in supported)

        val searchingRpm = "SEARCHING...\r41 0C 0F A0"
        assertFalse(ObdPidParser.isAdapterError(searchingRpm))
        assertEquals(1000, ObdPidParser.parseRpm(searchingRpm))

        assertTrue(ObdPidParser.isAdapterError("BUS INIT: ...ERROR"))
        assertTrue(ObdPidParser.isAdapterError("NO DATA"))
        assertTrue(ObdPidParser.isAdapterError("TIMEOUT"))
        assertTrue(ObdPidParser.isAdapterError("DISCONNECTED"))
    }

    @Test
    fun bleGattSelection_ignoresStandardServicesAndPrioritizesObdSerialServices() {
        val genericAccess = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
        val genericAttribute = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
        val heartRate = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        val obdFff0 = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val obdFfe0 = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val vgate18f0 = UUID.fromString("000018F0-0000-1000-8000-00805F9B34FB")
        val nordicUart = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

        assertTrue(ObdConnectionManager.isIgnoredStandardGattService(genericAccess))
        assertTrue(ObdConnectionManager.isIgnoredStandardGattService(genericAttribute))
        assertTrue(ObdConnectionManager.isIgnoredStandardGattService(heartRate))

        assertFalse(ObdConnectionManager.isIgnoredStandardGattService(obdFff0))
        assertFalse(ObdConnectionManager.isIgnoredStandardGattService(obdFfe0))
        assertFalse(ObdConnectionManager.isIgnoredStandardGattService(vgate18f0))
        assertFalse(ObdConnectionManager.isIgnoredStandardGattService(nordicUart))

        assertTrue(
            ObdConnectionManager.obdGattServicePriority(obdFff0) >
                ObdConnectionManager.obdGattServicePriority(nordicUart),
        )
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
