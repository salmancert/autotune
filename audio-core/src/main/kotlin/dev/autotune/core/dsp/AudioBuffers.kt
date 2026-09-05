package dev.autotune.core.dsp

import kotlin.math.min

/** Conversions between the capture format and the analysis format. */
object AudioBuffers {

    private const val SHORT_SCALE = 1f / 32768f

    /** Interleaved 16-bit PCM to mono floats in `[-1, 1]`. Returns frames written. */
    fun pcm16ToMono(input: ShortArray, inputLength: Int, channels: Int, out: FloatArray): Int {
        require(channels >= 1) { "channels must be positive" }
        val frames = min(inputLength / channels, out.size)
        var read = 0
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += input[read++] * SHORT_SCALE
            }
            out[frame] = sum / channels
        }
        return frames
    }

    /**
     * Unsigned 8-bit PCM (the format the platform Visualizer hands back) to mono
     * floats. The Visualizer waveform is centred on 128.
     */
    fun pcm8ToMono(input: ByteArray, inputLength: Int, out: FloatArray): Int {
        val frames = min(inputLength, out.size)
        for (i in 0 until frames) {
            out[i] = ((input[i].toInt() and 0xFF) - 128) / 128f
        }
        return frames
    }

    /**
     * Linear-interpolating decimator. Analysis runs at 16 kHz: dialogue lives
     * below 8 kHz, and the lower rate cuts the FFT cost roughly 3x versus 48 kHz.
     */
    fun resampleLinear(input: FloatArray, inputLength: Int, inputRate: Int, out: FloatArray, outputRate: Int): Int {
        if (inputRate == outputRate) {
            val n = min(inputLength, out.size)
            input.copyInto(out, 0, 0, n)
            return n
        }
        val ratio = inputRate.toDouble() / outputRate
        val frames = min((inputLength / ratio).toInt(), out.size)
        for (i in 0 until frames) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < inputLength) input[idx + 1] else a
            out[i] = a + (b - a) * frac
        }
        return frames
    }
}
