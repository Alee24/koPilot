package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class KenyanPlateSpeechTest {

    @Test
    fun formatForSpeech_addsFullStopsBetweenAllLetters() {
        assertEquals("K. D. A. 1 2 3 A.", KenyanPlateValidator.formatForSpeech("KDA 123A"))
        assertEquals("G. K. A. 1 2 3", KenyanPlateValidator.formatForSpeech("GK A123"))
        assertEquals("K. C. Z. 9 9 9 Z.", KenyanPlateValidator.formatForSpeech("kcz999z"))
    }
}
