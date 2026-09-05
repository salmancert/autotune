package dev.autotune.core.ml

/**
 * [AudioClassifier] backed by the bundled [Mlp], with a short posterior
 * smoother in front of the decision logic.
 *
 * Raw frame posteriors flicker on syllable boundaries; without smoothing the
 * stabiliser would chase them and the result would pump audibly.
 */
class MlpAudioClassifier(
    private val model: Mlp,
    /** Weight of a new frame in the posterior average; ~0.25 gives a ~150 ms memory. */
    private val smoothing: Float = 0.25f,
) : AudioClassifier {

    private val raw = FloatArray(model.outputSize)
    private val smoothed = FloatArray(model.outputSize)
    private var primed = false

    override fun classify(features: FloatArray, out: ClassScores) {
        model.predict(features, raw)
        if (!primed) {
            raw.copyInto(smoothed)
            primed = true
        } else {
            for (i in smoothed.indices) {
                smoothed[i] += smoothing * (raw[i] - smoothed[i])
            }
        }
        out.set(smoothed)
    }

    override fun reset() {
        primed = false
        java.util.Arrays.fill(smoothed, 0f)
    }

    /** Posterior before smoothing, for tests and the debug overlay. */
    fun lastRaw(): FloatArray = raw

    companion object {
        const val BUNDLED_RESOURCE = "/dev/autotune/core/ml/speech_music_mlp.model"

        /** The weights shipped inside the jar/apk. */
        fun bundledModel(): Mlp {
            val stream = MlpAudioClassifier::class.java.getResourceAsStream(BUNDLED_RESOURCE)
                ?: error("bundled model $BUNDLED_RESOURCE is missing from the build")
            return ModelIo.read(stream)
        }

        /** Loads the model shipped inside the jar/apk. */
        fun bundled(smoothing: Float = 0.25f): MlpAudioClassifier =
            MlpAudioClassifier(bundledModel(), smoothing)
    }
}
