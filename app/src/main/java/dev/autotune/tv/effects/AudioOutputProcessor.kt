package dev.autotune.tv.effects

import dev.autotune.core.engine.StabilizerConfig
import dev.autotune.core.engine.StabilizerState
import java.io.Closeable

/**
 * The output side: applies the engine's decision to audio the TV is already
 * playing.
 *
 * Autotune never touches the audio buffers of other apps - it cannot, and no app
 * can. What it does is attach a platform audio effect to the global output
 * session, which is the same mechanism every equaliser app on Android uses, and
 * then drive that effect's gain and compression from the engine.
 */
interface AudioOutputProcessor : Closeable {

    /** Short name for the status screen. */
    val label: String

    /** Pushes a fresh decision. Called at most every [MIN_UPDATE_INTERVAL_MS]. */
    fun apply(state: StabilizerState)

    /**
     * Configures a fixed dialogue-forward preset, used when no analysis source
     * is available. Static compression is not as good as the adaptive path, but
     * it is what keeps the app useful on a locked-down streaming app.
     */
    fun applyStaticPreset(config: StabilizerConfig)

    companion object {
        /**
         * Effect parameter writes cross into the audio HAL, so they are throttled;
         * the engine's own ballistics do the fine-grained smoothing.
         */
        const val MIN_UPDATE_INTERVAL_MS = 40L
    }
}
