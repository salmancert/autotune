package dev.autotune.core.features

/**
 * Analysis geometry. 16 kHz is deliberate: everything that separates dialogue
 * from score lives below 8 kHz, and the low rate keeps the whole chain inside a
 * couple of percent of one core on a cheap TV SoC.
 */
data class AnalysisFormat(
    val sampleRate: Int = 16_000,
    val frameSize: Int = 1024,
    val hopSize: Int = 512,
) {
    /** Analysis frames per second (~31.25 with the defaults). */
    val frameRate: Float get() = sampleRate.toFloat() / hopSize

    val frameDurationMs: Float get() = 1000f * frameSize / sampleRate

    val hopDurationMs: Float get() = 1000f * hopSize / sampleRate

    init {
        require(frameSize >= 2 && frameSize and (frameSize - 1) == 0) { "frameSize must be a power of two" }
        require(hopSize in 1..frameSize) { "hopSize must be within (0, frameSize]" }
    }

    companion object {
        val DEFAULT = AnalysisFormat()
    }
}
