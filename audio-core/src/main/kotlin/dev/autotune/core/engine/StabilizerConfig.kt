package dev.autotune.core.engine

/**
 * Everything the user (or a per-app profile) can tune.
 *
 * Levels are LUFS, the loudness unit broadcasters use; -20 LUFS is roughly the
 * level a streaming service aims for, and it is where dialogue is comfortable
 * on a TV at a normal volume setting.
 */
data class StabilizerConfig(
    val enabled: Boolean = true,

    /** Where dialogue should sit. Raise it if you still find speech quiet. */
    val targetDialogueLufs: Float = -20f,

    /** How far below the dialogue target sustained music is allowed to sit. */
    val musicCeilingOffsetDb: Float = 4f,

    /**
     * Hard ceiling above the dialogue target that *nothing* may exceed, whatever
     * the classifier thinks. This is the backstop that neutralises a sudden
     * stinger even when it is momentarily mistaken for dialogue.
     */
    val maxAboveTargetDb: Float = 2f,

    /** Most the stabiliser will lift quiet dialogue. */
    val maxBoostDb: Float = 12f,

    /** Most the stabiliser will pull down a loud passage. */
    val maxCutDb: Float = 15f,

    /** Dialogue levelling ballistics: deliberately slow, so speech is not pumped. */
    val dialogueAttackMs: Float = 400f,
    val dialogueReleaseMs: Float = 900f,

    /** Ducking ballistics: fast enough to catch a cut to loud music. */
    val duckAttackMs: Float = 40f,
    val duckReleaseMs: Float = 1200f,

    /** Below this the input is silence or room tone; hold the gain instead of amplifying hiss. */
    val noiseFloorLufs: Float = -55f,

    /** Presence lift in the 1-4 kHz consonant band while quiet dialogue is playing. */
    val dialogueClarityDb: Float = 3f,

    /** Low-shelf trim applied when loud low-frequency music or effects dominate. */
    val bassTrimDb: Float = 4f,

    /** 0 = bypass, 1 = full correction. The single "how much" knob in the UI. */
    val strength: Float = 1f,

    /** What is being watched. Presets set the values below; [ListeningPreset.CUSTOM] does not. */
    val preset: ListeningPreset = ListeningPreset.DEFAULT,

    /**
     * Extra attenuation for content that simply refuses to get quieter.
     *
     * The ceiling catches a peak. It does not catch an advert break that sits
     * exactly at the ceiling, brick-walled, for ninety seconds - which is
     * fatiguing in a way a loud moment is not. This trim builds while loudness
     * stays above the ceiling *and* the content has no dynamics left, and
     * releases as soon as either stops being true.
     */
    val sustainedTrimDb: Float = 6f,

    /** Grace period before the sustained trim starts to build. */
    val sustainedOnsetSeconds: Float = 4f,

    /** Time above the ceiling at which the sustained trim reaches its full depth. */
    val sustainedFullSeconds: Float = 20f,

    /** Compression ratio handed to the platform multiband compressor. */
    val compressionRatio: Float = 4f,

    /** Output limiter ceiling, dBFS. Leaves headroom so boosted dialogue cannot clip. */
    val limiterCeilingDb: Float = -1f,

    /** Posterior needed to switch the dominant class, which stops border flapping. */
    val classSwitchThreshold: Float = 0.55f,
) {
    /**
     * Effective settings once the preset has been applied.
     *
     * The user's own [targetDialogueLufs] and [strength] survive this: a preset
     * decides how hard to work, not how loud you like your television.
     */
    fun resolved(): StabilizerConfig = preset.configure(this)

    init {
        require(strength in 0f..1f) { "strength must be within [0, 1]" }
        require(maxBoostDb >= 0f && maxCutDb >= 0f) { "boost/cut limits must be non-negative" }
    }
}
