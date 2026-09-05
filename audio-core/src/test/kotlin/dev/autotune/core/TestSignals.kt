package dev.autotune.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Deterministic signal generators for the core tests. */
object TestSignals {

    fun sine(samples: Int, sampleRate: Int, frequency: Double, rmsDbfs: Double): FloatArray {
        val amplitude = 10.0.pow(rmsDbfs / 20.0) * sqrt(2.0)
        return FloatArray(samples) { (amplitude * sin(2.0 * PI * frequency * it / sampleRate)).toFloat() }
    }

    fun silence(samples: Int): FloatArray = FloatArray(samples)

    fun noise(samples: Int, rmsDbfs: Double, seed: Int = 1): FloatArray {
        val random = Random(seed)
        val raw = FloatArray(samples) { random.nextFloat() * 2f - 1f }
        return scaleToRms(raw, rmsDbfs)
    }

    /** A tone chopped by a syllable-rate envelope with gaps, as speech is. */
    fun syllabic(samples: Int, sampleRate: Int, rmsDbfs: Double, syllablesPerSecond: Double = 4.0): FloatArray {
        val carrier = sine(samples, sampleRate, 220.0, 0.0)
        val out = FloatArray(samples)
        for (i in 0 until samples) {
            val phase = 2.0 * PI * syllablesPerSecond * i / sampleRate
            val envelope = ((sin(phase) + 1.0) / 2.0).pow(3.0)
            // Every fifth syllable is a pause, which is what creates the gaps
            // speech has and a sustained note does not.
            val word = (i / (sampleRate * 1.25)).toInt() % 5 != 4
            out[i] = if (word) (carrier[i] * envelope).toFloat() else 0f
        }
        return scaleToRms(out, rmsDbfs)
    }

    fun rmsDbfs(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size)
        return 20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-12))
    }

    fun scaleToRms(samples: FloatArray, rmsDbfs: Double): FloatArray {
        val current = 10.0.pow(rmsDbfs(samples) / 20.0)
        if (current <= 1e-12) return samples
        val factor = (10.0.pow(rmsDbfs / 20.0) / current).toFloat()
        return FloatArray(samples.size) { samples[it] * factor }
    }
}
