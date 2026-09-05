package dev.autotune.core.engine

/**
 * What you are watching, as one choice on a remote control.
 *
 * Three sliders are a fine way to express *how* the stabiliser should behave and
 * a poor way to ask a person sitting on a sofa. A genre says the same thing in
 * one button press, because the genres genuinely differ: a film has a wide
 * cinematic range and needs the most work, the news is already levelled and
 * needs almost none, and sport is commentary riding on top of a crowd that
 * should stay audible rather than be ducked away.
 *
 * [CUSTOM] steps out of the way and uses the user's own settings unchanged.
 */
enum class ListeningPreset(
    val label: String,
    val summary: String,
    internal val configure: (StabilizerConfig) -> StabilizerConfig,
) {
    MOVIES(
        label = "Films & drama",
        summary = "Wide cinematic mixes: lifts quiet dialogue, holds the score well under it.",
        configure = {
            it.copy(
                musicCeilingOffsetDb = 5f,
                maxBoostDb = 12f,
                compressionRatio = 4.5f,
                dialogueClarityDb = 3f,
            )
        },
    ),

    SPORTS(
        label = "Sport",
        summary = "Keeps commentary clear over the crowd, and flattens the ad breaks.",
        configure = {
            it.copy(
                // The crowd is atmosphere, not noise: duck it less than a score,
                // but recover quickly, because a stadium swells every few seconds.
                musicCeilingOffsetDb = 3f,
                maxBoostDb = 10f,
                compressionRatio = 3.5f,
                dialogueClarityDb = 4f,
                duckReleaseMs = 700f,
                // Commercial breaks are the loudest thing in a broadcast.
                sustainedTrimDb = 8f,
            )
        },
    ),

    LATE_NIGHT(
        label = "Late night",
        summary = "Everyone else is asleep: dialogue up, everything else firmly down.",
        configure = {
            it.copy(
                musicCeilingOffsetDb = 8f,
                maxAboveTargetDb = 0f,
                maxBoostDb = 15f,
                maxCutDb = 18f,
                compressionRatio = 6f,
                duckReleaseMs = 900f,
                sustainedTrimDb = 8f,
            )
        },
    ),

    NEWS(
        label = "News & talk",
        summary = "Already level: a light touch, with consonants pushed forward.",
        configure = {
            it.copy(
                musicCeilingOffsetDb = 3f,
                maxBoostDb = 8f,
                compressionRatio = 3f,
                dialogueClarityDb = 4f,
                // Stings between items are short; do not chase them.
                duckAttackMs = 80f,
            )
        },
    ),

    CUSTOM(
        label = "Custom",
        summary = "Your own settings, used exactly as they are.",
        configure = { it },
    );

    companion object {
        val DEFAULT = MOVIES

        fun fromName(name: String?): ListeningPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
