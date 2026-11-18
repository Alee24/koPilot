package ke.co.kopilot.feature.soundstudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure DSP math helpers for offline engine sound synthesis & multi-layer WAV crossfading.
 * Unit-testable on the JVM with zero Android dependencies.
 */
object EngineSynthMath {
    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * Firing frequency in Hz for a 4-stroke engine: (RPM / 60) * (cylinders / 2).
     */
    fun firingFrequencyHz(rpm: Float, cylinderCount: Int, fundamentalScale: Float): Float {
        val clampedRpm = rpm.coerceIn(300f, 10_000f)
        val cyl = cylinderCount.coerceIn(1, 12)
        return (clampedRpm / 60f) * (cyl / 2f) * fundamentalScale
    }

    /**
     * Soft-clipping limiter using hyperbolic tangent so output never clips harshly
     * and stays below conservative safety ceiling.
     */
    fun softLimit(sample: Float, ceiling: Float = 0.85f): Float {
        return tanh(sample.toDouble()).toFloat() * ceiling
    }

    /**
     * Computes crossfade weights across custom RPM sample layers.
     */
    fun computeLayerWeights(
        rpm: Float,
        rpmDeltaPerSec: Float,
        layers: List<EngineSampleLayer>,
    ): Map<RpmSampleSlot, Float> {
        if (layers.isEmpty()) return emptyMap()
        val weights = mutableMapOf<RpmSampleSlot, Float>()
        var sum = 0f
        for (layer in layers) {
            val w = when (layer.slot) {
                RpmSampleSlot.ACCEL -> if (rpmDeltaPerSec > 250f && rpm in layer.minRpm.toFloat()..layer.maxRpm.toFloat()) {
                    ((rpmDeltaPerSec - 250f) / 1200f).coerceIn(0f, 0.7f)
                } else 0f
                RpmSampleSlot.DECEL -> if (rpmDeltaPerSec < -250f && rpm in layer.minRpm.toFloat()..layer.maxRpm.toFloat()) {
                    ((-rpmDeltaPerSec - 250f) / 1200f).coerceIn(0f, 0.7f)
                } else 0f
                else -> {
                    val minR = layer.minRpm.toFloat()
                    val maxR = layer.maxRpm.toFloat().coerceAtLeast(minR + 100f)
                    val margin = 350f
                    when {
                        rpm < minR - margin || rpm > maxR + margin -> 0f
                        rpm < minR -> ((rpm - (minR - margin)) / margin).coerceIn(0f, 1f)
                        rpm > maxR -> (((maxR + margin) - rpm) / margin).coerceIn(0f, 1f)
                        else -> 1f
                    }
                }
            }
            if (w > 0.001f) {
                weights[layer.slot] = w
                sum += w
            }
        }
        if (sum <= 0f) {
            val nearest = layers.minByOrNull { abs(it.baseRpm - rpm) } ?: return emptyMap()
            return mapOf(nearest.slot to 1f)
        }
        return weights.mapValues { it.value / sum }
    }

    /**
     * Synthesizes one mono sample for the selected built-in engine profile using
     * phase-continuous multi-harmonic synthesis.
     */
    fun synthesizeProceduralSample(
        phase: Float,
        turboPhase: Float,
        rpm: Float,
        throttle: Float,
        rpmDeltaPerSec: Float,
        profile: EngineSoundProfile,
        sportMode: Boolean,
        turboEnabled: Boolean,
    ): Float {
        val warmth = profile.exhaustWarmth
        val growl = profile.intakeGrowl
        val sportBoost = if (sportMode) 1.25f else 1.0f

        // Fundamental firing order + sub-octave rumble + upper harmonics
        val h0 = sin((phase * 0.5f) * TWO_PI) * (0.28f * warmth)
        val h1 = sin(phase * TWO_PI) * 0.48f
        val h2 = sin((phase * 2.0f) * TWO_PI) * (0.22f + 0.16f * throttle * sportBoost)
        val h3 = sin((phase * 3.0f) * TWO_PI) * (0.10f + 0.14f * growl * throttle)
        val h4 = sin((phase * 4.0f) * TWO_PI) * (0.05f + 0.10f * growl * sportBoost)

        // Diesel high-compression pulse shaping
        val dieselKnock = if (profile.category.isDiesel) {
            val pulse = sin((phase * 6.0f) * TWO_PI) * 0.14f + sin((phase * 8.0f) * TWO_PI) * 0.08f
            pulse * (0.6f + 0.4f * throttle)
        } else {
            0f
        }

        // Optional turbo spool whistle under load + lift-off flutter
        val turboWhistle = if (turboEnabled && profile.turboSpoolAmount > 0.05f) {
            val spoolLevel = ((rpm - 1400f) / 3000f).coerceIn(0f, 1f) * throttle * profile.turboSpoolAmount
            val flutter = if (rpmDeltaPerSec < -400f) 0.12f * profile.turboSpoolAmount else 0f
            sin(turboPhase * TWO_PI) * (0.14f * spoolLevel + flutter)
        } else {
            0f
        }

        val loadGain = 0.42f + 0.58f * throttle
        val raw = (h0 + h1 + h2 + h3 + h4 + dieselKnock) * loadGain + turboWhistle
        return raw * (if (sportMode) 1.15f else 0.95f)
    }
}
