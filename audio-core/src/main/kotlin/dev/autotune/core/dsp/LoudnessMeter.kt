package dev.autotune.core.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * ITU-R BS.1770-4 loudness meter (mono/downmixed input).
 *
 * Broadcast uses LUFS because it tracks perceived loudness far better than peak
 * or plain RMS, which is exactly the difference between "the dialogue is quiet"
 * and "the score is deafening" even when both hit the same sample peak.
 *
 * Two integration windows are maintained:
 *  - momentary (400 ms) drives the fast music/effects duck;
 *  - short-term (3 s) drives slow dialogue levelling.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    momentaryWindowMs: Int = 400,
    shortTermWindowMs: Int = 3000,
) {
    /** Loudness reported when the window holds nothing but digital silence. */
    companion object {
        const val SILENCE_LUFS = -100.0f
        private const val ENERGY_FLOOR = 1e-12f
    }

    private val shelf = Biquad.kWeightingShelf(sampleRate)
    private val highPass = Biquad.kWeightingHighPass(sampleRate)

    private val momentary = SlidingMeanSquare(sampleRate * momentaryWindowMs / 1000)
    private val shortTerm = SlidingMeanSquare(sampleRate * shortTermWindowMs / 1000)

    /** Feeds mono samples in `[-1, 1]`; returns the momentary loudness in LUFS. */
    fun process(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): Float {
        for (i in offset until offset + length) {
            val weighted = highPass.process(shelf.process(samples[i]))
            val energy = weighted * weighted
            momentary.add(energy)
            shortTerm.add(energy)
        }
        return momentaryLufs
    }

    val momentaryLufs: Float
        get() = toLufs(momentary.mean())

    val shortTermLufs: Float
        get() = toLufs(shortTerm.mean())

    fun reset() {
        shelf.reset()
        highPass.reset()
        momentary.reset()
        shortTerm.reset()
    }

    private fun toLufs(meanSquare: Float): Float {
        if (meanSquare <= ENERGY_FLOOR) return SILENCE_LUFS
        return (-0.691 + 10.0 * log10(meanSquare.toDouble())).toFloat()
    }
}

/**
 * Constant-time sliding mean of squared samples.
 *
 * The running sum is rebuilt from the ring buffer every [REFRESH_INTERVAL]
 * samples: adding and subtracting floats for hours otherwise accumulates drift.
 */
internal class SlidingMeanSquare(windowSamples: Int) {
    private val window = max(1, windowSamples)
    private val ring = FloatArray(window)
    private var index = 0
    private var filled = 0
    private var sum = 0.0
    private var sinceRefresh = 0

    fun add(energy: Float) {
        sum -= ring[index]
        ring[index] = energy
        sum += energy
        index = (index + 1) % window
        if (filled < window) filled++
        if (++sinceRefresh >= REFRESH_INTERVAL) {
            sinceRefresh = 0
            var exact = 0.0
            for (i in 0 until filled) exact += ring[i]
            sum = exact
        }
    }

    fun mean(): Float {
        if (filled == 0) return 0f
        return (max(0.0, sum) / filled).toFloat()
    }

    fun reset() {
        java.util.Arrays.fill(ring, 0f)
        index = 0
        filled = 0
        sum = 0.0
        sinceRefresh = 0
    }

    private companion object {
        const val REFRESH_INTERVAL = 48_000
    }
}
