package dev.autotune.core.engine

import dev.autotune.core.ml.AudioClass

/**
 * What the engine wants the output chain to do right now.
 *
 * Recomputed every analysis hop (~32 ms) and read by the Android layer, which
 * pushes it into the platform effects.
 */
data class StabilizerState(
    /** Broadband gain to apply, dB. Negative ducks, positive lifts dialogue. */
    val gainDb: Float = 0f,

    /** Presence lift for the 1-4 kHz consonant band, dB. */
    val dialogueEqDb: Float = 0f,

    /** Low-shelf trim for booming music and effects, dB (negative or zero). */
    val bassTrimDb: Float = 0f,

    /** Limiter ceiling handed to the output stage, dBFS. */
    val limiterCeilingDb: Float = -1f,

    /** Compression ratio handed to the output stage. */
    val compressionRatio: Float = 4f,

    val momentaryLufs: Float = LoudnessSilence,
    val shortTermLufs: Float = LoudnessSilence,

    /** Speech-gated estimate of the scene's dialogue level; NaN until speech is heard. */
    val dialogueLevelLufs: Float = Float.NaN,

    val speechProbability: Float = 0f,
    val musicProbability: Float = 0f,
    val effectsProbability: Float = 0f,

    /** Class currently driving the decision, after hysteresis. */
    val dominantClass: AudioClass = AudioClass.EFFECTS,

    /** Extra attenuation from sustained, dynamics-free loudness (advert breaks). */
    val sustainedTrimDb: Float = 0f,

    /** True while the correction is faded out for an A/B comparison. */
    val bypassed: Boolean = false,

    /** False while the input is below the noise floor - the gain is frozen. */
    val hasSignal: Boolean = false,

    /** Profile in force, so the UI can show which app was detected. */
    val profile: AppProfile = AppProfile.GENERIC,
) {
    companion object {
        const val LoudnessSilence = -100f
    }
}
