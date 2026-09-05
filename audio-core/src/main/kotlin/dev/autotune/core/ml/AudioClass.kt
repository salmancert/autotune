package dev.autotune.core.ml

/** What the classifier believes the current audio mostly is. */
enum class AudioClass {
    /** Dialogue or narration, including dialogue mixed over a quiet score. */
    SPEECH,

    /** Score, songs, stingers - anything musical without dominant dialogue. */
    MUSIC,

    /** Effects and ambience: explosions, engines, crowds, rain, room tone. */
    EFFECTS;

    companion object {
        val ORDERED = entries.toTypedArray()
        const val COUNT = 3
    }
}

/** Class posterior for one analysis frame. */
class ClassScores {
    val probabilities = FloatArray(AudioClass.COUNT)

    val speech: Float get() = probabilities[AudioClass.SPEECH.ordinal]
    val music: Float get() = probabilities[AudioClass.MUSIC.ordinal]
    val effects: Float get() = probabilities[AudioClass.EFFECTS.ordinal]

    val argMax: AudioClass
        get() {
            var best = 0
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > probabilities[best]) best = i
            }
            return AudioClass.ORDERED[best]
        }

    fun set(values: FloatArray) {
        values.copyInto(probabilities, 0, 0, AudioClass.COUNT)
    }

    override fun toString(): String =
        "speech=%.2f music=%.2f effects=%.2f".format(speech, music, effects)
}

/**
 * Anything that can label a feature snapshot.
 *
 * The bundled implementation is [MlpAudioClassifier]; the app module can swap in
 * a TensorFlow Lite model (YAMNet and friends) behind the same interface.
 */
interface AudioClassifier {
    /** Writes the posterior for [features] into [out]. Must not allocate. */
    fun classify(features: FloatArray, out: ClassScores)

    /** Drops any internal history. */
    fun reset() {}
}
