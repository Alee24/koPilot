package ke.co.kopilot.feature.soundstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SoundStudioTests {

    @Test
    fun smoother_tracksIdleAndGradualAcceleration() {
        val smoother = ObdRpmSmoother()
        val idle = smoother.update(
            obdConnected = true,
            rawRpm = 800,
            rawThrottlePct = 15f,
            rawEngineLoadPct = 20f,
            sampleTimestampMs = 1_000L,
            nowMs = 1_050L,
            responsiveness = 0.75f,
            dtSec = 0.05f,
        )
        assertFalse(idle.isStale)
        assertEquals(800f, idle.smoothedRpm, 1f)
        assertTrue(idle.safeOutputScale > 0f)

        // Gradual acceleration toward 2400 RPM
        var latest = idle
        for (step in 1..15) {
            val t = 1_000L + step * 100L
            latest = smoother.update(
                obdConnected = true,
                rawRpm = 800 + step * 100,
                rawThrottlePct = 45f,
                rawEngineLoadPct = 55f,
                sampleTimestampMs = t,
                nowMs = t + 20L,
                responsiveness = 0.8f,
                dtSec = 0.1f,
            )
        }
        assertTrue("Expected smoothed RPM to rise significantly", latest.smoothedRpm > 1800f)
        assertTrue("Expected positive rpmDeltaPerSec during accel", latest.rpmDeltaPerSec > 500f)
    }

    @Test
    fun smoother_detectsRapidRpmIncreaseAndDecel() {
        val smoother = ObdRpmSmoother()
        smoother.update(true, 1200, 20f, 25f, 1_000L, 1_010L, 0.9f, 0.05f)
        val rapidAccel = smoother.update(true, 4200, 85f, 90f, 1_500L, 1_510L, 0.9f, 0.05f)
        assertTrue("Expected high positive delta", rapidAccel.rpmDeltaPerSec > 4000f)

        val decel = smoother.update(true, 2200, 0f, 15f, 2_000L, 2_010L, 0.9f, 0.05f)
        assertTrue("Expected negative delta on decel", decel.rpmDeltaPerSec < -2000f)
    }

    @Test
    fun smoother_handlesCvtMissingThrottleFallback() {
        val smoother = ObdRpmSmoother()
        val result = smoother.update(
            obdConnected = true,
            rawRpm = 2800,
            rawThrottlePct = null, // CVT / vehicle where PID 0111 is unsupported
            rawEngineLoadPct = 68f,
            sampleTimestampMs = 2_000L,
            nowMs = 2_050L,
            responsiveness = 0.75f,
            dtSec = 0.05f,
        )
        assertFalse(result.isStale)
        assertTrue("Throttle fallback should derive from engine load", result.throttleFraction > 0.25f)
    }

    @Test
    fun smoother_fadesOutSafelyOnDisconnectAndStaleData() {
        val smoother = ObdRpmSmoother(staleTimeoutMs = 3_500L)
        // Build up active state
        var active = smoother.update(true, 3000, 50f, 50f, 1_000L, 1_020L, 0.8f, 0.5f)
        active = smoother.update(true, 3000, 50f, 50f, 1_500L, 1_520L, 0.8f, 0.5f)
        assertTrue(active.safeOutputScale > 0.5f)

        // Stale sample (> 3500ms old)
        val stale = smoother.update(true, 3000, 50f, 50f, 1_500L, 6_000L, 0.8f, 0.2f)
        assertTrue(stale.isStale)
        assertTrue("Safe scale must decrease on stale data", stale.safeOutputScale < active.safeOutputScale)

        // Disconnect fade-out to zero
        var disconnected = stale
        repeat(10) {
            disconnected = smoother.update(false, null, null, null, 1_500L, 6_500L + it * 100L, 0.8f, 0.2f)
        }
        assertEquals(0f, disconnected.safeOutputScale, 0.001f)
    }

    @Test
    fun wavLoader_validatesPcm16AndRejectsInvalidHeaders() {
        val invalid = WavAudioLoader.parseAndValidateWav("bad.wav", "NOT_A_WAV_FILE_DATA_12345678901234567890".toByteArray())
        assertTrue(invalid is WavValidationResult.Invalid)

        val tooShort = createTestPcm16Wav(sampleRate = 22_050, durationSec = 0.1f)
        assertTrue(WavAudioLoader.parseAndValidateWav("short.wav", tooShort) is WavValidationResult.Invalid)

        val validBytes = createTestPcm16Wav(sampleRate = 22_050, durationSec = 0.6f)
        val valid = WavAudioLoader.parseAndValidateWav("idle.wav", validBytes)
        assertTrue("Expected valid WAV parse", valid is WavValidationResult.Valid)
        val audio = (valid as WavValidationResult.Valid).audio
        assertEquals(22_050, audio.sampleRateHz)
        // Resampled to 44.1 kHz (~26,460 samples for 0.6s)
        assertTrue(audio.monoPcm44100.size > 24_000)
    }

    @Test
    fun synthMath_firingFrequencyLimiterAndCrossfadeWeights() {
        assertEquals(100f, EngineSynthMath.firingFrequencyHz(3000f, 4, 1.0f), 0.01f)
        assertEquals(200f, EngineSynthMath.firingFrequencyHz(3000f, 8, 1.0f), 0.01f)

        val limitedPos = EngineSynthMath.softLimit(10.0f, ceiling = 0.85f)
        val limitedNeg = EngineSynthMath.softLimit(-10.0f, ceiling = 0.85f)
        assertTrue(limitedPos <= 0.85f && limitedPos > 0.8f)
        assertTrue(limitedNeg >= -0.85f && limitedNeg < -0.8f)

        val layers = listOf(
            EngineSampleLayer(RpmSampleSlot.IDLE, "idle.wav", 600, 1200, 800, 44100, 1.0f),
            EngineSampleLayer(RpmSampleSlot.LOW, "low.wav", 1000, 2600, 1800, 44100, 1.0f),
            EngineSampleLayer(RpmSampleSlot.ACCEL, "accel.wav", 1500, 6500, 3500, 44100, 1.0f),
        )
        val weights = EngineSynthMath.computeLayerWeights(rpm = 2000f, rpmDeltaPerSec = 900f, layers = layers)
        assertTrue(weights.containsKey(RpmSampleSlot.LOW))
        assertTrue(weights.containsKey(RpmSampleSlot.ACCEL))
        val sum = weights.values.sum()
        assertEquals(1.0f, sum, 0.01f)
        assertTrue(abs(weights.values.sum() - 1f) < 0.01f)
    }

    private fun createTestPcm16Wav(sampleRate: Int, durationSec: Float): ByteArray {
        val sampleCount = (sampleRate * durationSec).toInt()
        val dataBytes = sampleCount * 2
        val totalBytes = 44 + dataBytes
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalBytes - 8)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // PCM format
        buf.putShort(1) // Mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // Byte rate
        buf.putShort(2) // Block align
        buf.putShort(16) // Bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (i in 0 until sampleCount) {
            val s = (sin(2.0 * PI * 120.0 * i / sampleRate) * 16000.0).toInt().toShort()
            buf.putShort(s)
        }
        return buf.array()
    }
}
