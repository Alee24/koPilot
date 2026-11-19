package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KenyanPlateValidatorTest {

    @Test
    fun `validates standard, GK, diplomatic, EAC, and international plates and masks middle characters`() {
        val std = KenyanPlateValidator.validateFormat("KDA123A")
        assertNotNull(std)
        assertEquals("KDA 123A", std?.first)
        assertEquals("Civilian Standard", std?.second)
        assertEquals("KDA ***A", KenyanPlateValidator.maskForDisplay(std!!.first))

        val gk = KenyanPlateValidator.validateFormat("GK B456")
        assertNotNull(gk)
        assertEquals("GK B456", gk?.first)

        val un = KenyanPlateValidator.validateFormat("UN 1234")
        assertNotNull(un)
        assertEquals("UN 1234", un?.first)

        val eac = KenyanPlateValidator.validateFormat("UAB456C")
        assertNotNull(eac)
        assertEquals("UAB 456C", eac?.first)

        val uk = KenyanPlateValidator.validateFormat("AB12CDE")
        assertNotNull(uk)
        assertEquals("AB12 CDE", uk?.first)
    }

    @Test
    fun `rejects invalid, vehicle brand, speed limit, or uncertain plate strings without guessing characters`() {
        assertNull(KenyanPlateValidator.validateFormat("K?A 123A"))
        assertNull(KenyanPlateValidator.validateFormat("HELLO WORLD"))
        assertNull(KenyanPlateValidator.validateFormat("123"))
        assertNull(KenyanPlateValidator.validateFormat("SPEED 80"))
        assertNull(KenyanPlateValidator.validateFormat("LIMIT 50"))
        assertNull(KenyanPlateValidator.validateFormat("TOYOTA 123"))
    }
}
