package dev.autotune.training

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureExtractor
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.ClassScores
import dev.autotune.core.ml.MlpAudioClassifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the weights that are actually committed to the repository.
 *
 * The corpus here uses a seed the model has never been trained on, so a
 * regression in the feature code, the corpus or the weights shows up as a drop
 * in accuracy rather than as a silent behaviour change on someone's TV.
 */
class BundledModelTest {

    private val format = AnalysisFormat.DEFAULT

    @Test
    fun `the bundled model classifies unseen synthetic content`() {
        val clips = SyntheticCorpus().generate(seed = 555_001, clips = 120, durationSeconds = 4f)
        val classifier = MlpAudioClassifier.bundled()
        val scores = ClassScores()

        var frames = 0
        var frameHits = 0
        var clipHits = 0
        val confusion = Array(AudioClass.COUNT) { IntArray(AudioClass.COUNT) }

        for (clip in clips) {
            val extractor = FeatureExtractor(format)
            classifier.reset()
            val votes = IntArray(AudioClass.COUNT)
            extractor.process(clip.samples, 0, clip.samples.size) { _, features ->
                if (extractor.isPrimed) {
                    classifier.classify(features, scores)
                    val predicted = scores.argMax
                    votes[predicted.ordinal]++
                    confusion[clip.label.ordinal][predicted.ordinal]++
                    frames++
                    if (predicted == clip.label) frameHits++
                }
            }
            val winner = votes.indices.maxBy { votes[it] }
            if (winner == clip.label.ordinal) clipHits++
        }

        val frameAccuracy = frameHits.toFloat() / frames
        val clipAccuracy = clipHits.toFloat() / clips.size
        val report = buildString {
            appendLine("frame accuracy $frameAccuracy over $frames frames, clip accuracy $clipAccuracy")
            for (truth in AudioClass.ORDERED) {
                appendLine("  ${truth.name}: ${confusion[truth.ordinal].toList()}")
            }
        }
        println(report)

        assertTrue("frame accuracy too low\n$report", frameAccuracy > 0.85f)
        assertTrue("clip accuracy too low\n$report", clipAccuracy > 0.90f)
    }

    @Test
    fun `dialogue mixed over a score is still recognised as dialogue`() {
        // The case the whole app exists for: if this regresses, the stabiliser
        // starts ducking the very lines the user is trying to hear.
        val corpus = SyntheticCorpus()
        val random = kotlin.random.Random(4242)
        val length = format.sampleRate * 5
        val classifier = MlpAudioClassifier.bundled()
        val scores = ClassScores()

        var correct = 0
        val trials = 25
        repeat(trials) {
            val speech = corpus.speech(random, length)
            SignalTools.normalizeTo(speech, -20f)
            val bed = corpus.music(random, length)
            SignalTools.normalizeTo(bed, -30f)
            SignalTools.mixInto(speech, bed, 1f)
            SignalTools.clip(speech)

            val extractor = FeatureExtractor(format)
            classifier.reset()
            val votes = IntArray(AudioClass.COUNT)
            extractor.process(speech, 0, speech.size) { _, features ->
                if (extractor.isPrimed) {
                    classifier.classify(features, scores)
                    votes[scores.argMax.ordinal]++
                }
            }
            if (votes.indices.maxBy { votes[it] } == AudioClass.SPEECH.ordinal) correct++
        }
        assertTrue("only $correct of $trials speech-over-music clips were called speech", correct >= 22)
    }
}
