package dev.autotune.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBuffersTest {

    @Test
    fun `stereo 16-bit is downmixed to mono floats`() {
        val input = shortArrayOf(16384, -16384, 32767, 32767)
        val out = FloatArray(2)
        val frames = AudioBuffers.pcm16ToMono(input, input.size, channels = 2, out = out)
        assertEquals(2, frames)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-3f)
    }

    @Test
    fun `unsigned 8-bit visualizer data is centred on zero`() {
        val input = byteArrayOf(128.toByte(), 255.toByte(), 0)
        val out = FloatArray(3)
        assertEquals(3, AudioBuffers.pcm8ToMono(input, input.size, out))
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.9922f, out[1], 1e-3f)
        assertEquals(-1f, out[2], 1e-3f)
    }

    @Test
    fun `decimating 48k to 16k keeps a third of the samples and the waveform`() {
        val input = FloatArray(480) { kotlin.math.sin(2.0 * Math.PI * 100.0 * it / 48_000).toFloat() }
        val out = FloatArray(200)
        val written = AudioBuffers.resampleLinear(input, input.size, 48_000, out, 16_000)
        assertEquals(160, written)
        for (i in 0 until written) {
            val expected = kotlin.math.sin(2.0 * Math.PI * 100.0 * i / 16_000).toFloat()
            assertEquals(expected, out[i], 2e-3f)
        }
    }
}
