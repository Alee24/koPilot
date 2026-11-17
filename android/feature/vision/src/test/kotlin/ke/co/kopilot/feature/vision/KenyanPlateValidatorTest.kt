package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KenyanPlateValidatorTest {

    @Test
    fun `validates standard, GK, and diplomatic Kenyan plates and masks middle characters`() {
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
    }

    @Test
    fun `rejects invalid or uncertain plate strings without guessing characters`() {
        assertNull(KenyanPlateValidator.validateFormat("K?A 123A"))
        assertNull(KenyanPlateValidator.validateFormat("HELLO WORLD"))
        assertNull(KenyanPlateValidator.validateFormat("123"))
    }
}
