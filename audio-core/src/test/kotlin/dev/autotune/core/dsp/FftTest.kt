package dev.autotune.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun `impulse has a flat power spectrum`() {
        val size = 256
        val fft = Fft(size)
        val input = FloatArray(size).also { it[0] = 1f }
        val power = FloatArray(size / 2 + 1)
        fft.powerSpectrum(input, FloatArray(size), FloatArray(size), power)
        for (bin in power.indices) {
            assertEquals("bin $bin", 1.0, power[bin].toDouble(), 1e-4)
        }
    }

    @Test
    fun `a sine lands in its own bin`() {
        val size = 1024
        val binIndex = 40
        val fft = Fft(size)
        val input = FloatArray(size) { sin(2.0 * PI * binIndex * it / size).toFloat() }
        val power = FloatArray(size / 2 + 1)
        fft.powerSpectrum(input, FloatArray(size), FloatArray(size), power)

        val peak = power.indices.maxBy { power[it] }
        assertEquals(binIndex, peak)
        // Everything away from the peak should be numerically negligible.
        val leakage = power.filterIndexed { index, _ -> kotlin.math.abs(index - binIndex) > 1 }.max()
        assertTrue("leakage $leakage", leakage < power[binIndex] * 1e-6)
    }

    @Test
    fun `power spectrum obeys Parseval`() {
        val size = 512
        val fft = Fft(size)
        val input = FloatArray(size) { sin(0.1 * it).toFloat() + 0.5f * sin(0.37 * it).toFloat() }
        val power = FloatArray(size / 2 + 1)
        fft.powerSpectrum(input, FloatArray(size), FloatArray(size), power)

        val timeEnergy = input.sumOf { it.toDouble() * it }
        // Bins 1..N/2-1 appear twice in the full spectrum.
        var spectralEnergy = power[0].toDouble() + power[size / 2]
        for (bin in 1 until size / 2) spectralEnergy += 2.0 * power[bin]
        assertEquals(timeEnergy, spectralEnergy / size, timeEnergy * 1e-4)
    }
}
