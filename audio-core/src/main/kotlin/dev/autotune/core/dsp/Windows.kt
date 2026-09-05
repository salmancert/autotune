package dev.autotune.core.dsp

import kotlin.math.PI
import kotlin.math.cos

/** Analysis windows. */
object Windows {

    /** Periodic Hann window, the right choice for overlapping STFT analysis. */
    fun hann(size: Int): FloatArray = FloatArray(size) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat()
    }
}
