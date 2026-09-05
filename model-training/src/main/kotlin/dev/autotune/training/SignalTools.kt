package dev.autotune.training

import dev.autotune.core.dsp.Biquad
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Building blocks for the synthetic corpus. */
object SignalTools {

    fun mixInto(target: FloatArray, source: FloatArray, gain: Float) {
        val n = minOf(target.size, source.size)
        for (i in 0 until n) target[i] += source[i] * gain
    }

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    /** Scales [samples] so its RMS sits at [targetDbfs]. */
    fun normalizeTo(samples: FloatArray, targetDbfs: Float) {
        val current = rms(samples)
        if (current <= 1e-9f) return
        val gain = (10.0.pow(targetDbfs / 20.0) / current).toFloat()
        for (i in samples.indices) samples[i] *= gain
    }

    /** Hard-clips to `[-1, 1]` the way a real output stage would. */
    fun clip(samples: FloatArray) {
        for (i in samples.indices) samples[i] = samples[i].coerceIn(-1f, 1f)
    }

    fun whiteNoise(random: Random, length: Int): FloatArray =
        FloatArray(length) { (random.nextFloat() * 2f - 1f) }

    /** Exponential attack/decay envelope, the shape of a hit or an explosion. */
    fun percussiveEnvelope(length: Int, sampleRate: Int, attackMs: Float, decayMs: Float): FloatArray {
        val attack = max(1, (attackMs * sampleRate / 1000f).toInt())
        val decay = max(1.0, (decayMs * sampleRate / 1000.0))
        return FloatArray(length) { i ->
            if (i < attack) i.toFloat() / attack
            else exp(-(i - attack) / decay).toFloat()
        }
    }

    /**
     * Cheap Schroeder reverb. Rooms smear envelopes, and an envelope-based
     * classifier that never saw a room would fall over in a living room mix.
     */
    fun reverberate(samples: FloatArray, sampleRate: Int, mix: Float, random: Random) {
        if (mix <= 0f) return
        val combDelays = intArrayOf(
            (0.0297 * sampleRate).toInt(),
            (0.0371 * sampleRate).toInt(),
            (0.0411 * sampleRate).toInt(),
        )
        val feedback = 0.72f + random.nextFloat() * 0.12f
        val wet = FloatArray(samples.size)
        for (delay in combDelays) {
            val buffer = FloatArray(delay)
            var index = 0
            for (i in samples.indices) {
                val delayed = buffer[index]
                wet[i] += delayed / combDelays.size
                buffer[index] = samples[i] + delayed * feedback
                index = (index + 1) % delay
            }
        }
        for (i in samples.indices) {
            samples[i] = samples[i] * (1f - mix) + wet[i] * mix
        }
    }

    fun bandPass(samples: FloatArray, sampleRate: Int, frequency: Double, q: Double): FloatArray {
        val filter = Biquad.bandPass(sampleRate, frequency, q)
        return FloatArray(samples.size) { filter.process(samples[it]) }
    }

    fun lowPass(samples: FloatArray, sampleRate: Int, frequency: Double, q: Double = 0.707): FloatArray {
        val filter = Biquad.lowPass(sampleRate, frequency, q)
        return FloatArray(samples.size) { filter.process(samples[it]) }
    }

    fun highPass(samples: FloatArray, sampleRate: Int, frequency: Double, q: Double = 0.707): FloatArray {
        val filter = Biquad.highPass(sampleRate, frequency, q)
        return FloatArray(samples.size) { filter.process(samples[it]) }
    }

    fun sine(length: Int, sampleRate: Int, frequency: Double, phase: Double = 0.0): FloatArray {
        val step = 2.0 * PI * frequency / sampleRate
        return FloatArray(length) { sin(phase + step * it).toFloat() }
    }
}
