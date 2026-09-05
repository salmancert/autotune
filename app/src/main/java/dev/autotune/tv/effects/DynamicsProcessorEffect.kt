package dev.autotune.tv.effects

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.autotune.core.engine.StabilizerConfig
import dev.autotune.core.engine.StabilizerState
import kotlin.math.abs

/**
 * The good path: `DynamicsProcessing` (API 28+), which gives a pre-EQ, a
 * multiband compressor and a limiter on the output mix.
 *
 * The split of work matters. The engine's gain reacts on the scale of tens of
 * milliseconds but is always slightly behind the audio, because analysing sound
 * takes time. The multiband compressor and limiter run *inside* the audio path
 * with no such delay, so they catch the instantaneous transient while the engine
 * handles the slower judgement of "this is a loud music cue, hold it down".
 */
@RequiresApi(Build.VERSION_CODES.P)
class DynamicsProcessorEffect private constructor(
    private val effect: DynamicsProcessing,
) : AudioOutputProcessor {

    override val label: String = "multiband compressor + limiter"

    private var lastGain = Float.NaN
    private var lastDialogueEq = Float.NaN
    private var lastBassTrim = Float.NaN

    override fun apply(state: StabilizerState) {
        runCatching {
            if (changed(lastGain, state.gainDb)) {
                effect.setInputGainAllChannelsTo(state.gainDb)
                lastGain = state.gainDb
            }
            if (changed(lastBassTrim, state.bassTrimDb)) {
                effect.setPreEqBandAllChannelsTo(BAND_LOW, band(CUTOFF_LOW, state.bassTrimDb))
                lastBassTrim = state.bassTrimDb
            }
            if (changed(lastDialogueEq, state.dialogueEqDb)) {
                effect.setPreEqBandAllChannelsTo(BAND_PRESENCE, band(CUTOFF_PRESENCE, state.dialogueEqDb))
                lastDialogueEq = state.dialogueEqDb
            }
        }.onFailure { Log.w(TAG, "could not update effect", it) }
    }

    override fun applyStaticPreset(config: StabilizerConfig) {
        runCatching {
            val resolved = config.resolved()
            effect.setInputGainAllChannelsTo(0f)
            effect.setPreEqBandAllChannelsTo(BAND_LOW, band(CUTOFF_LOW, -resolved.bassTrimDb * 0.5f))
            effect.setPreEqBandAllChannelsTo(BAND_BODY, band(CUTOFF_BODY, 0f))
            effect.setPreEqBandAllChannelsTo(BAND_PRESENCE, band(CUTOFF_PRESENCE, resolved.dialogueClarityDb))
            effect.setPreEqBandAllChannelsTo(BAND_AIR, band(CUTOFF_AIR, 0f))
            configureCompressor(effect, resolved, aggressive = true)
            lastGain = 0f
            lastDialogueEq = resolved.dialogueClarityDb
            lastBassTrim = -resolved.bassTrimDb * 0.5f
        }.onFailure { Log.w(TAG, "could not apply static preset", it) }
    }

    override fun setBypassed(bypassed: Boolean) {
        runCatching { effect.enabled = !bypassed }
            .onFailure { Log.w(TAG, "could not toggle the effect", it) }
    }

    override fun close() {
        runCatching { effect.enabled = false }
        runCatching { effect.release() }
    }

    private fun changed(previous: Float, next: Float): Boolean =
        previous.isNaN() || abs(previous - next) >= UPDATE_EPSILON_DB

    private fun band(cutoffHz: Float, gainDb: Float) =
        DynamicsProcessing.EqBand(true, cutoffHz, gainDb)

    companion object {
        private const val TAG = "DynamicsProcessor"

        /** Session 0 is the global output mix. */
        private const val GLOBAL_OUTPUT_SESSION = 0

        private const val CHANNELS = 2
        private const val BAND_COUNT = 4
        private const val UPDATE_EPSILON_DB = 0.1f

        private const val BAND_LOW = 0
        private const val BAND_BODY = 1
        private const val BAND_PRESENCE = 2
        private const val BAND_AIR = 3

        /** Band edges chosen around speech: rumble, body, consonants, air. */
        private const val CUTOFF_LOW = 250f
        private const val CUTOFF_BODY = 1000f
        private const val CUTOFF_PRESENCE = 4000f
        private const val CUTOFF_AIR = 16000f

        fun create(config: StabilizerConfig, sessionId: Int = GLOBAL_OUTPUT_SESSION): DynamicsProcessorEffect? = try {
            val resolved = config.resolved()
            val effectConfig = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                /* preEqInUse = */ true,
                /* preEqBandCount = */ BAND_COUNT,
                /* mbcInUse = */ true,
                /* mbcBandCount = */ BAND_COUNT,
                /* postEqInUse = */ false,
                /* postEqBandCount = */ 0,
                /* limiterInUse = */ true,
            ).build()

            val effect = DynamicsProcessing(0, sessionId, effectConfig)
            configureCompressor(effect, resolved, aggressive = false)
            effect.setPreEqBandAllChannelsTo(BAND_LOW, DynamicsProcessing.EqBand(true, CUTOFF_LOW, 0f))
            effect.setPreEqBandAllChannelsTo(BAND_BODY, DynamicsProcessing.EqBand(true, CUTOFF_BODY, 0f))
            effect.setPreEqBandAllChannelsTo(BAND_PRESENCE, DynamicsProcessing.EqBand(true, CUTOFF_PRESENCE, 0f))
            effect.setPreEqBandAllChannelsTo(BAND_AIR, DynamicsProcessing.EqBand(true, CUTOFF_AIR, 0f))
            effect.enabled = true
            DynamicsProcessorEffect(effect)
        } catch (error: Exception) {
            Log.w(TAG, "DynamicsProcessing unavailable on session $sessionId", error)
            null
        }

        /**
         * Bands are compressed unevenly on purpose: the low band, where
         * explosions and film-score bass live, is squeezed hardest, and the
         * speech band is left comparatively alone so dialogue keeps its shape.
         */
        private fun configureCompressor(
            effect: DynamicsProcessing,
            config: StabilizerConfig,
            aggressive: Boolean,
        ) {
            val ratio = config.compressionRatio * if (aggressive) 1.25f else 1f
            val bands = arrayOf(
                mbcBand(CUTOFF_LOW, ratio * 1.2f, threshold = -28f, attack = 6f, release = 180f),
                mbcBand(CUTOFF_BODY, ratio, threshold = -26f, attack = 10f, release = 220f),
                mbcBand(CUTOFF_PRESENCE, ratio * 0.6f, threshold = -22f, attack = 15f, release = 260f),
                mbcBand(CUTOFF_AIR, ratio * 0.8f, threshold = -24f, attack = 8f, release = 200f),
            )
            bands.forEachIndexed { index, band -> effect.setMbcBandAllChannelsTo(index, band) }
            effect.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true,
                    /* enabled = */ true,
                    /* linkGroup = */ 0,
                    /* attackTime = */ 1f,
                    /* releaseTime = */ 60f,
                    /* ratio = */ 10f,
                    /* threshold = */ config.limiterCeilingDb,
                    /* postGain = */ 0f,
                ),
            )
        }

        private fun mbcBand(
            cutoffHz: Float,
            ratio: Float,
            threshold: Float,
            attack: Float,
            release: Float,
        ) = DynamicsProcessing.MbcBand(
            /* enabled = */ true,
            /* cutoffFrequency = */ cutoffHz,
            /* attackTime = */ attack,
            /* releaseTime = */ release,
            /* ratio = */ ratio,
            /* threshold = */ threshold,
            /* kneeWidth = */ 6f,
            /* noiseGateThreshold = */ -80f,
            /* expanderRatio = */ 1f,
            /* preGain = */ 0f,
            /* postGain = */ 0f,
        )
    }
}
