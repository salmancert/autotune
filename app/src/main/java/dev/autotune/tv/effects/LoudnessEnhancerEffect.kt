package dev.autotune.tv.effects

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import dev.autotune.core.engine.StabilizerConfig
import dev.autotune.core.engine.StabilizerState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fallback for devices without `DynamicsProcessing`.
 *
 * `LoudnessEnhancer` can only add gain, never remove it, so the trick is to run
 * at a standing boost and modulate around it: quiet dialogue gets the full
 * boost, a loud cue gets it taken away. The user turns their TV volume down a
 * few notches once, and from then on the range in between is ours to work with.
 */
class LoudnessEnhancerEffect private constructor(
    private val effect: LoudnessEnhancer,
    private val standingGainDb: Float,
) : AudioOutputProcessor {

    override val label: String = "loudness enhancer (limited)"

    private var lastMillibels = Int.MIN_VALUE

    override fun apply(state: StabilizerState) {
        setGain(standingGainDb + state.gainDb)
    }

    override fun applyStaticPreset(config: StabilizerConfig) {
        setGain(standingGainDb * config.resolved().strength)
    }

    private fun setGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(0f, MAX_GAIN_DB)
        val millibels = (clamped * 100).roundToInt()
        if (abs(millibels - lastMillibels) < UPDATE_EPSILON_MB) return
        runCatching {
            effect.setTargetGain(millibels)
            lastMillibels = millibels
        }.onFailure { Log.w(TAG, "could not set target gain", it) }
    }

    override fun close() {
        runCatching { effect.enabled = false }
        runCatching { effect.release() }
    }

    companion object {
        private const val TAG = "LoudnessEnhancer"
        private const val GLOBAL_OUTPUT_SESSION = 0
        private const val MAX_GAIN_DB = 16f
        private const val UPDATE_EPSILON_MB = 10

        fun create(standingGainDb: Float = 8f, sessionId: Int = GLOBAL_OUTPUT_SESSION): LoudnessEnhancerEffect? = try {
            val effect = LoudnessEnhancer(sessionId)
            effect.setTargetGain((standingGainDb * 100).roundToInt())
            effect.enabled = true
            LoudnessEnhancerEffect(effect, standingGainDb)
        } catch (error: Exception) {
            Log.w(TAG, "LoudnessEnhancer unavailable on session $sessionId", error)
            null
        }
    }
}
