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
     * asymmetric combustion-pulse shaping, cylinder-bank modulation, band-shaped
     * exhaust turbulence noise, overrun crackles, and warm saturation.
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
        noiseSample: Float = 0f,
    ): Float {
        val warmth = profile.exhaustWarmth
        val growl = profile.intakeGrowl
        val sportBoost = if (sportMode) 1.28f else 1.0f
        val isV8OrBass = profile.category == EngineCategory.V8_MUSCLE || profile.category == EngineCategory.CLASSIC_ENGINE
        val isExotic = profile.category == EngineCategory.V10_SUPERCAR || profile.category == EngineCategory.V12_SUPERCAR

        // Asymmetric combustion pulse wave (sharper attack than pure sine -> realistic exhaust slap)
        val saw = (phase * 2f - 1f)
        val combPulse = tanh((saw * (1.8f + 1.4f * throttle)).toDouble()).toFloat() * 0.42f

        // Sub-harmonic crank/bank lope (gives Mustang / Bass / Classic V8 its deep chop)
        val lopeGain = if (isV8OrBass) 0.42f * warmth else 0.16f * warmth
        val subHalf = sin((phase * 0.5f) * TWO_PI) * lopeGain
        val subQuarter = if (isV8OrBass) sin((phase * 0.25f) * TWO_PI) * (0.24f * warmth) else 0f

        // Core firing harmonics + exotic upper scream
        val h1 = sin(phase * TWO_PI) * 0.45f
        val h2 = sin((phase * 2.0f) * TWO_PI) * (0.30f + 0.18f * throttle * sportBoost)
        val h3 = sin((phase * 3.0f) * TWO_PI) * (0.22f + 0.18f * growl * throttle)
        val h4 = sin((phase * 4.0f) * TWO_PI) * (0.16f + (if (isExotic) 0.22f else 0.12f) * growl * sportBoost)
        val h6 = sin((phase * 6.0f) * TWO_PI) * (if (isExotic) 0.18f else 0.10f) * (0.6f + 0.4f * growl)
        val h8 = sin((phase * 8.0f) * TWO_PI) * (if (isExotic) 0.12f else 0.06f) * (0.5f + 0.5f * throttle)

        // Diesel high-compression injection clatter & big-rig low thump
        val dieselKnock = if (profile.category.isDiesel) {
            val knock = tanh((sin((phase * 6.0f) * TWO_PI) * 1.9f).toDouble()).toFloat() * 0.24f +
                sin((phase * 9.0f) * TWO_PI) * 0.12f
            knock * (0.7f + 0.3f * throttle)
        } else {
            0f
        }

        // Exhaust air turbulence noise (modulated by firing phase so it breathes with each stroke)
        val strokeEnv = 0.55f + 0.45f * sin(phase * TWO_PI)
        val turbulence = noiseSample * strokeEnv * (0.18f + 0.26f * throttle + 0.10f * growl)

        // Overrun / lift-off exhaust crackle when decelerating from mid/high RPM
        val decelOverrun = if (rpmDeltaPerSec < -320f && rpm > 1400f) {
            val crackle = if (abs(noiseSample) > 0.72f) noiseSample * 0.35f else 0f
            crackle * (if (sportMode || isV8OrBass || isExotic) 1.2f else 0.6f)
        } else {
            0f
        }

        // Turbocharger spool whistle + wastegate flutter
        val turboWhistle = if (turboEnabled && profile.turboSpoolAmount > 0.05f) {
            val spoolLevel = ((rpm - 1100f) / 2800f).coerceIn(0f, 1f) * (0.35f + 0.65f * throttle) * profile.turboSpoolAmount
            val flutter = if (rpmDeltaPerSec < -350f) 0.16f * profile.turboSpoolAmount else 0f
            sin(turboPhase * TWO_PI) * (0.18f * spoolLevel + flutter)
        } else {
            0f
        }

        val loadGain = 0.58f + 0.42f * throttle
        val drive = 1.35f + (warmth * 0.65f) + (if (sportMode) 0.35f else 0f)
        val combined = (combPulse + subHalf + subQuarter + h1 + h2 + h3 + h4 + h6 + h8 + dieselKnock + turbulence) * loadGain +
            decelOverrun + turboWhistle

        return tanh((combined * drive).toDouble()).toFloat() * 0.92f
    }
}
