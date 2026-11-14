package ke.co.kopilot.feature.alerts

import ke.co.kopilot.core.common.model.AlertMessage
import ke.co.kopilot.core.common.model.AlertPriority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlertCoordinatorTest {

    private class FakeSpeechSink : SpeechSink {
        val spoken = mutableListOf<String>()
        override fun speak(text: String, flushQueue: Boolean, speechRate: Float, languageTag: String) {
            spoken.add(text)
        }
        override fun stop() = Unit
    }

    @Test
    fun `duplicate suppression and temporary mute behave deterministically`() {
        val sink = FakeSpeechSink()
        val coordinator = LocalAlertCoordinator(sink)
        val alert = AlertMessage(
            id = "a1",
            priority = AlertPriority.ROAD_OBSERVATION,
            spokenText = "50 kilometres per hour sign observed.",
            displayText = "50 km/h sign observed",
            dedupKey = "sign_50",
            timestampMillis = 10_000L,
        )

        assertTrue(coordinator.submit(alert, nowMillis = 10_000L))
        // Within 20s cooldown -> suppressed
        assertFalse(coordinator.submit(alert, nowMillis = 15_000L))
        // After cooldown -> allowed
        assertTrue(coordinator.submit(alert, nowMillis = 35_000L))

        // Mute for 5 minutes -> suppressed
        coordinator.muteForMinutes(5, nowMillis = 40_000L)
        assertFalse(coordinator.submit(alert.copy(dedupKey = "sign_60"), nowMillis = 50_000L))
    }
}
