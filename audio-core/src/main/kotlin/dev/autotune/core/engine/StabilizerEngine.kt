package dev.autotune.core.engine

import dev.autotune.core.dsp.LoudnessMeter
import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureVector
import dev.autotune.core.features.FrameFeatures
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.AudioClassifier
import dev.autotune.core.ml.ClassScores
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.core.features.FeatureExtractor
import kotlin.math.min

/**
 * The stabiliser proper: mono analysis audio in, a [StabilizerState] out.
 *
 * The control law has three parts.
 *
 *  1. **Dialogue levelling.** When the model says speech, the short-term
 *     loudness is pulled toward [StabilizerConfig.targetDialogueLufs] slowly, so
 *     a quiet mumbled line comes up without the levelling itself being audible.
 *
 *  2. **Music/effects ducking.** When the model says music or effects, the
 *     momentary loudness is held under a ceiling a few dB below the dialogue
 *     target, with a fast attack so a cut to a loud score is caught inside a
 *     few tens of milliseconds, and a slow release so it does not breathe.
 *
 *  3. **A classifier-independent backstop.** Whatever the model believes,
 *     nothing is allowed more than [StabilizerConfig.maxAboveTargetDb] above the
 *     dialogue target. A misclassified stinger is still neutralised; the model
 *     decides how *gracefully* it is handled, not *whether* it is handled.
 *
 *  4. **A sustained-loudness trim.** The three rules above all react to level.
 *     None of them catches an advert break that sits exactly at the ceiling,
 *     brick-walled, for ninety seconds - loud in a way that is fatiguing rather
 *     than startling. That gets extra attenuation the longer it goes on.
 *
 * The engine is single-threaded and allocation-free once constructed.
 */
class StabilizerEngine(
    baseConfig: StabilizerConfig = StabilizerConfig(),
    val format: AnalysisFormat = AnalysisFormat.DEFAULT,
    private val classifier: AudioClassifier = MlpAudioClassifier.bundled(),
) {
    private val extractor = FeatureExtractor(format)
    private val meter = LoudnessMeter(format.sampleRate)
    private val scores = ClassScores()
    private val smoother = GainSmoother(
        attackMs = baseConfig.duckAttackMs,
        releaseMs = baseConfig.duckReleaseMs,
        stepMs = format.hopDurationMs,
    )

    private var dominant = AudioClass.EFFECTS
    private var pendingClass = AudioClass.EFFECTS
    private var pendingFrames = 0
    private var dialogueEqDb = 0f
    private var bassTrimDb = 0f
    private var sustainedSeconds = 0f
    private var sustainedTrimDb = 0f

    /**
     * Running estimate of how loud the dialogue in this scene is, updated only
     * while speech is actually playing.
     *
     * The obvious choice - the 3 s short-term loudness - is wrong here, and
     * audibly so: for three seconds after a loud cue it still reads the cue, so
     * the quiet line that follows gets *cut* at exactly the moment the viewer is
     * leaning in to hear it. A speech-gated estimate remembers the dialogue
     * level across the cue and has the correct gain ready the instant speech
     * returns.
     */
    private var dialogueLevelLufs = Float.NaN

    /** User settings, before the preset and the per-app profile are applied. */
    var config: StabilizerConfig = baseConfig
        set(value) {
            field = value
            effective = resolve(value, profile)
        }

    /** Profile of the app currently playing. */
    var profile: AppProfile = AppProfile.GENERIC
        set(value) {
            if (field == value) return
            field = value
            effective = resolve(config, value)
        }

    private var effective: StabilizerConfig = resolve(baseConfig, AppProfile.GENERIC)

    /**
     * Temporarily fades the correction out without forgetting anything.
     *
     * This is the A/B switch: the fastest way to answer "is this actually doing
     * anything?" is to turn it off for ten seconds. It fades rather than jumps,
     * because a step change in gain on live audio is an audible click.
     */
    @Volatile
    var bypassed: Boolean = false

    /** Latest decision. Safe to read from another thread; the reference is swapped atomically. */
    @Volatile
    var state: StabilizerState = StabilizerState(limiterCeilingDb = effective.limiterCeilingDb)
        private set

    /**
     * Feeds mono samples at [AnalysisFormat.sampleRate] and returns the newest
     * state. Any block size is accepted; the input is walked one hop at a time
     * so that the loudness meter and the feature frames stay aligned.
     */
    fun process(samples: FloatArray, length: Int = samples.size): StabilizerState {
        val hop = format.hopSize
        var offset = 0
        while (offset < length) {
            val chunk = min(hop, length - offset)
            meter.process(samples, offset, chunk)
            extractor.process(samples, offset, chunk) { frame, features ->
                classifier.classify(features, scores)
                update(frame, features)
            }
            offset += chunk
        }
        return state
    }

    /**
     * Order matters: the preset sets the values, then the app being watched
     * nudges them. Nudging first would be pointless - the preset would overwrite
     * whatever the nudge had done.
     */
    private fun resolve(config: StabilizerConfig, profile: AppProfile): StabilizerConfig =
        profile.configure(config.resolved())

    private fun update(frame: FrameFeatures, features: FloatArray) {
        val cfg = effective
        val momentary = meter.momentaryLufs
        val shortTerm = meter.shortTermLufs
        val hasSignal = momentary > cfg.noiseFloorLufs

        if (!cfg.enabled || bypassed) {
            // Fade out rather than reset: a step change in gain on audio that is
            // already playing is a click.
            smoother.setTimes(BYPASS_FADE_MS, BYPASS_FADE_MS)
            val gain = smoother.step(0f)
            dialogueEqDb += EQ_SMOOTHING * (0f - dialogueEqDb)
            bassTrimDb += EQ_SMOOTHING * (0f - bassTrimDb)
            sustainedSeconds = 0f
            sustainedTrimDb = 0f
            publish(momentary, shortTerm, gain, hasSignal)
            return
        }

        if (!hasSignal || !extractor.isPrimed) {
            // Silence, or not enough history yet: freeze rather than guess.
            publish(momentary, shortTerm, smoother.hold(), hasSignal)
            return
        }

        updateDominantClass(cfg)

        val speech = scores.speech
        val loud = scores.music + scores.effects

        if (speech > SPEECH_GATE) {
            dialogueLevelLufs = if (dialogueLevelLufs.isNaN()) {
                momentary
            } else {
                dialogueLevelLufs + DIALOGUE_LEVEL_COEFFICIENT * (momentary - dialogueLevelLufs)
            }
        }

        // 1. Slow levelling toward the dialogue target.
        val dialogueReference = if (dialogueLevelLufs.isNaN()) shortTerm else dialogueLevelLufs
        val dialogueGain = (cfg.targetDialogueLufs - dialogueReference)
            .coerceIn(-MAX_SPEECH_CUT_DB, cfg.maxBoostDb)

        // 2. Fast duck toward the music ceiling.
        val ceilingLufs = cfg.targetDialogueLufs - cfg.musicCeilingOffsetDb
        val duckGain = min(0f, ceilingLufs - momentary).coerceAtLeast(-cfg.maxCutDb)

        var target = speech * dialogueGain + loud * duckGain

        // 3. Backstop that does not trust the classifier.
        val hardCeilingGain = cfg.targetDialogueLufs + cfg.maxAboveTargetDb - momentary
        target = min(target, hardCeilingGain)

        // 4. Extra trim for loudness that simply will not stop.
        updateSustainedTrim(cfg, momentary, ceilingLufs, features)
        target += sustainedTrimDb

        target = target.coerceIn(-cfg.maxCutDb, cfg.maxBoostDb) * cfg.strength

        // A large drop is always urgent, whatever the class: that is the blast
        // the user is complaining about.
        val urgent = target < smoother.currentDb - URGENT_DROP_DB
        if (urgent || dominant != AudioClass.SPEECH) {
            smoother.setTimes(cfg.duckAttackMs, cfg.duckReleaseMs)
        } else {
            smoother.setTimes(cfg.dialogueAttackMs, cfg.dialogueReleaseMs)
        }
        val gain = smoother.step(target)

        // Tone shaping rides along with the gain, smoothed so it cannot zip.
        val quietness = ((cfg.targetDialogueLufs - shortTerm) / CLARITY_RANGE_DB).coerceIn(0f, 1f)
        val targetClarity = cfg.dialogueClarityDb * speech * quietness * cfg.strength
        val boominess = (((momentary - ceilingLufs) / BASS_RANGE_DB).coerceIn(0f, 1f)) *
            ((frame.bandLow - BASS_SHARE_FLOOR) / BASS_SHARE_RANGE).coerceIn(0f, 1f)
        val targetBassTrim = -cfg.bassTrimDb * loud * boominess * cfg.strength

        dialogueEqDb += EQ_SMOOTHING * (targetClarity - dialogueEqDb)
        bassTrimDb += EQ_SMOOTHING * (targetBassTrim - bassTrimDb)

        publish(momentary, shortTerm, gain, hasSignal = true)
    }

    /**
     * Builds the sustained trim while the content is both loud and lifeless.
     *
     * Both conditions matter. Loud alone is the ceiling's job and would catch a
     * dramatic climax that is *meant* to be loud. What distinguishes an advert
     * break, a shopping channel or a badly mastered upload is that it is loud
     * *and* has had every dynamic taken out of it: measured across the corpus,
     * clean dialogue varies by about 24 dB inside a two-second window, a film
     * score by 2 dB, and brick-walled advertising audio by under half a dB.
     */
    private fun updateSustainedTrim(
        cfg: StabilizerConfig,
        momentary: Float,
        ceilingLufs: Float,
        features: FloatArray,
    ) {
        val levelSpread = features[FeatureVector.LEVEL_STD]
        val flatness = ((SUSTAINED_DYNAMICS_KNEE_DB - levelSpread) / SUSTAINED_DYNAMICS_RANGE_DB)
            .coerceIn(0f, 1f)
        val hop = format.hopDurationMs / 1000f

        if (momentary > ceilingLufs && flatness > 0f) {
            sustainedSeconds += hop * flatness
        } else {
            // Let it go quickly: the moment the content breathes again, or drops
            // below the ceiling, this should stop applying.
            sustainedSeconds -= hop * SUSTAINED_DECAY_RATE
        }
        sustainedSeconds = sustainedSeconds.coerceIn(0f, cfg.sustainedFullSeconds)

        val progress = ((sustainedSeconds - cfg.sustainedOnsetSeconds) /
            (cfg.sustainedFullSeconds - cfg.sustainedOnsetSeconds).coerceAtLeast(0.1f))
            .coerceIn(0f, 1f)
        // Smoothstep, so the trim eases in instead of arriving as a ramp.
        val eased = progress * progress * (3f - 2f * progress)
        val targetTrim = -cfg.sustainedTrimDb * eased
        sustainedTrimDb += SUSTAINED_SMOOTHING * (targetTrim - sustainedTrimDb)
    }

    private fun updateDominantClass(cfg: StabilizerConfig) {
        val best = scores.argMax
        val bestProbability = scores.probabilities[best.ordinal]
        if (best == dominant) {
            pendingFrames = 0
            pendingClass = best
            return
        }
        if (bestProbability < cfg.classSwitchThreshold) {
            pendingFrames = 0
            return
        }
        if (best == pendingClass) {
            if (++pendingFrames >= CLASS_DWELL_FRAMES) {
                dominant = best
                pendingFrames = 0
            }
        } else {
            pendingClass = best
            pendingFrames = 1
        }
    }

    private fun publish(momentary: Float, shortTerm: Float, gain: Float, hasSignal: Boolean) {
        state = StabilizerState(
            gainDb = gain,
            dialogueEqDb = dialogueEqDb,
            bassTrimDb = bassTrimDb,
            limiterCeilingDb = effective.limiterCeilingDb,
            compressionRatio = effective.compressionRatio,
            momentaryLufs = momentary,
            shortTermLufs = shortTerm,
            dialogueLevelLufs = dialogueLevelLufs,
            speechProbability = scores.speech,
            musicProbability = scores.music,
            effectsProbability = scores.effects,
            dominantClass = dominant,
            sustainedTrimDb = sustainedTrimDb,
            hasSignal = hasSignal,
            bypassed = bypassed,
            profile = profile,
        )
    }

    fun reset() {
        extractor.reset()
        meter.reset()
        classifier.reset()
        smoother.reset(0f)
        dominant = AudioClass.EFFECTS
        pendingClass = AudioClass.EFFECTS
        pendingFrames = 0
        dialogueEqDb = 0f
        bassTrimDb = 0f
        sustainedSeconds = 0f
        sustainedTrimDb = 0f
        dialogueLevelLufs = Float.NaN
        state = StabilizerState(limiterCeilingDb = effective.limiterCeilingDb)
    }

    private companion object {
        /** Speech is only ever pulled down gently; it is never the problem. */
        const val MAX_SPEECH_CUT_DB = 6f

        /** A target this far below the current gain is treated as an emergency duck. */
        const val URGENT_DROP_DB = 3f

        /** Consecutive hops a new class must win before it takes over (~100 ms). */
        const val CLASS_DWELL_FRAMES = 3

        /** Posterior above which a frame contributes to the dialogue level estimate. */
        const val SPEECH_GATE = 0.5f

        /** One-pole coefficient for a ~2 s memory of the dialogue level at a 32 ms hop. */
        const val DIALOGUE_LEVEL_COEFFICIENT = 0.016f

        /** Level spread, in dB, below which content counts as having no dynamics left. */
        const val SUSTAINED_DYNAMICS_KNEE_DB = 2.5f
        const val SUSTAINED_DYNAMICS_RANGE_DB = 2f

        /** The trim releases this many times faster than it builds. */
        const val SUSTAINED_DECAY_RATE = 4f
        const val SUSTAINED_SMOOTHING = 0.05f

        /** Fade applied when bypassing, long enough not to click. */
        const val BYPASS_FADE_MS = 250f

        const val CLARITY_RANGE_DB = 6f
        const val BASS_RANGE_DB = 6f
        const val BASS_SHARE_FLOOR = 0.3f
        const val BASS_SHARE_RANGE = 0.4f
        const val EQ_SMOOTHING = 0.15f
    }
}
