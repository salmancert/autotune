package dev.autotune.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Allocation-free iterative radix-2 FFT.
 *
 * A single instance is reused for every audio frame on the analysis thread, so
 * the twiddle tables and the bit-reversal permutation are computed once.
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val cosTable = FloatArray(size / 2) { cos(-2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            table[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** In-place complex FFT of [real]/[imag], both of length [size]. */
    fun forward(real: FloatArray, imag: FloatArray) {
        require(real.size == size && imag.size == size) { "buffers must be $size samples" }

        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }

        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val partner = j + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = real[partner] * wr - imag[partner] * wi
                    val ti = real[partner] * wi + imag[partner] * wr
                    real[partner] = real[j] - tr
                    imag[partner] = imag[j] - ti
                    real[j] += tr
                    imag[j] += ti
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }

    /**
     * Power spectrum of a real signal.
     *
     * [input] holds [size] samples, [scratchImag] is a reusable work buffer and
     * [magnitudes] receives `size / 2 + 1` bins of |X(k)|^2.
     */
    fun powerSpectrum(input: FloatArray, scratchReal: FloatArray, scratchImag: FloatArray, magnitudes: FloatArray) {
        input.copyInto(scratchReal, 0, 0, size)
        java.util.Arrays.fill(scratchImag, 0f)
        forward(scratchReal, scratchImag)
        for (bin in 0..size / 2) {
            val re = scratchReal[bin]
            val im = scratchImag[bin]
            magnitudes[bin] = re * re + im * im
        }
    }
}
