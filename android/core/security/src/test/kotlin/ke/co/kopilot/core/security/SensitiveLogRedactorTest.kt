package ke.co.kopilot.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLogRedactorTest {
    @Test
    fun `redact strips bearer tokens and api keys`() {
        val raw = "Authorization: Bearer my.jwt.token ek_live_1234567890 AIzaSyDummyKey1234567890123"
        val redacted = SensitiveLogRedactor.redact(raw)
        assertFalse(redacted.contains("my.jwt.token"))
        assertFalse(redacted.contains("ek_live_1234567890"))
        assertFalse(redacted.contains("AIzaSyDummyKey"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}
