package dev.autotune.training

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * A cached harmonic wavetable.
 *
 * Summing 40 harmonics per sample is the obvious way to build a glottal pulse or
 * a bowed note, and it is also ~3 billion sine evaluations for a corpus this
 * size. The waveform only depends on the harmonic count and the roll-off, so it
 * is built once and read back by phase.
 */
class Wavetable private constructor(private val table: FloatArray) {

    /** Reads the table at [phase] in turns (fractional part is what counts). */
    fun sample(phase: Double): Float {
        val size = table.size
        var position = (phase - Math.floor(phase)) * size
        val index = position.toInt()
        val frac = (position - index).toFloat()
        val a = table[index]
        val b = table[if (index + 1 == size) 0 else index + 1]
        return a + (b - a) * frac
    }

    companion object {
        private const val SIZE = 4096
        private val cache = HashMap<Pair<Int, Double>, Wavetable>()

        /** Table of `sum_k sin(2*pi*k*t) / k^rolloff` for `k <= harmonics`. */
        @Synchronized
        fun harmonic(harmonics: Int, rolloff: Double): Wavetable = cache.getOrPut(harmonics to rolloff) {
            val table = FloatArray(SIZE)
            for (i in 0 until SIZE) {
                val t = 2.0 * PI * i / SIZE
                var value = 0.0
                for (k in 1..harmonics) value += sin(t * k) / k.toDouble().pow(rolloff)
                table[i] = value.toFloat()
            }
            Wavetable(table)
        }

        /**
         * Picks a table whose highest harmonic stays under Nyquist for [frequency],
         * the usual mip-map trick for avoiding audible aliasing.
         */
        fun bandLimited(frequency: Double, sampleRate: Int, maxHarmonics: Int, rolloff: Double): Wavetable {
            val affordable = ((sampleRate / 2.0) / frequency).toInt().coerceIn(1, maxHarmonics)
            // Quantise to a few levels so the cache stays small.
            val level = LEVELS.first { it >= affordable || it == LEVELS.last() }
            return harmonic(minOf(level, maxHarmonics), rolloff)
        }

        private val LEVELS = intArrayOf(2, 4, 8, 12, 20, 30, 40)
    }
}
