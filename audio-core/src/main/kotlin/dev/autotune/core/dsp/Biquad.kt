package dev.autotune.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct-form-II transposed biquad. Coefficients are normalised by a0 on
 * construction so the hot loop is five multiplies and two adds.
 */
class Biquad(
    b0: Double,
    b1: Double,
    b2: Double,
    a0: Double,
    a1: Double,
    a2: Double,
) {
    private val b0 = (b0 / a0).toFloat()
    private val b1 = (b1 / a0).toFloat()
    private val b2 = (b2 / a0).toFloat()
    private val a1 = (a1 / a0).toFloat()
    private val a2 = (a2 / a0).toFloat()

    private var s1 = 0f
    private var s2 = 0f

    fun reset() {
        s1 = 0f
        s2 = 0f
    }

    fun process(sample: Float): Float {
        val out = b0 * sample + s1
        s1 = b1 * sample - a1 * out + s2
        s2 = b2 * sample - a2 * out
        return out
    }

    /** Filters [samples] in place over `[0, length)`. */
    fun processInPlace(samples: FloatArray, length: Int = samples.size) {
        for (i in 0 until length) {
            samples[i] = process(samples[i])
        }
    }

    companion object {

        /**
         * Stage 1 of the ITU-R BS.1770 K-weighting curve: a high-frequency
         * shelf approximating the acoustic effect of the head.
         *
         * The published coefficients are defined at 48 kHz; they are re-derived
         * here for an arbitrary rate so analysis at 16 kHz stays correct.
         */
        fun kWeightingShelf(sampleRate: Int): Biquad {
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val fc = 1681.974450955533
            val k = kotlin.math.tan(PI * fc / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val denom = 1.0 + k / q + k * k
            return Biquad(
                b0 = (vh + vb * k / q + k * k) / denom,
                b1 = 2.0 * (k * k - vh) / denom,
                b2 = (vh - vb * k / q + k * k) / denom,
                a0 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / denom,
                a2 = (1.0 - k / q + k * k) / denom,
            )
        }

        /**
         * Stage 2 of K-weighting: the ~38 Hz RLB high-pass. Uses the standard's
         * own `[1, -2, 1]` numerator rather than the generic RBJ normalisation.
         */
        fun kWeightingHighPass(sampleRate: Int): Biquad {
            val fc = 38.13547087602444
            val q = 0.5003270373238773
            val k = kotlin.math.tan(PI * fc / sampleRate)
            val denom = 1.0 + k / q + k * k
            return Biquad(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a0 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / denom,
                a2 = (1.0 - k / q + k * k) / denom,
            )
        }

        fun highPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return Biquad(
                b0 = (1.0 + cosW0) / 2.0,
                b1 = -(1.0 + cosW0),
                b2 = (1.0 + cosW0) / 2.0,
                a0 = 1.0 + alpha,
                a1 = -2.0 * cosW0,
                a2 = 1.0 - alpha,
            )
        }

        fun lowPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return Biquad(
                b0 = (1.0 - cosW0) / 2.0,
                b1 = 1.0 - cosW0,
                b2 = (1.0 - cosW0) / 2.0,
                a0 = 1.0 + alpha,
                a1 = -2.0 * cosW0,
                a2 = 1.0 - alpha,
            )
        }

        fun bandPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return Biquad(
                b0 = alpha,
                b1 = 0.0,
                b2 = -alpha,
                a0 = 1.0 + alpha,
                a1 = -2.0 * cosW0,
                a2 = 1.0 - alpha,
            )
        }

        fun peaking(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return Biquad(
                b0 = 1.0 + alpha * a,
                b1 = -2.0 * cosW0,
                b2 = 1.0 - alpha * a,
                a0 = 1.0 + alpha / a,
                a1 = -2.0 * cosW0,
                a2 = 1.0 - alpha / a,
            )
        }

        fun lowShelf(sampleRate: Int, frequency: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / 0.7071 - 1) + 2)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            return Biquad(
                b0 = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha),
                b1 = 2 * a * ((a - 1) - (a + 1) * cosW0),
                b2 = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha),
                a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha,
                a1 = -2 * ((a - 1) + (a + 1) * cosW0),
                a2 = (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha,
            )
        }
    }
}
