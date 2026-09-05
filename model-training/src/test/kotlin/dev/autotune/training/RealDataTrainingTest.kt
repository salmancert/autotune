package dev.autotune.training

import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.training.data.LabelEntry
import dev.autotune.training.data.LabelManifest
import dev.autotune.training.data.WavIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Exercises the path a user actually takes: labelled recordings on disk, mixed
 * with the synthetic corpus, fine-tuned from the shipped weights.
 *
 * The "recordings" here are synthesised episodes rather than real television -
 * no test can depend on someone's downloads - but they travel the whole
 * pipeline: WAV files, a manifest, file-level splitting, warm start, and the
 * per-source report.
 */
class RealDataTrainingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sampleRate = 16_000

    /** Writes fake episodes with a known dialogue/score layout and their labels. */
    private fun buildCorpusOnDisk(files: Int, source: String): List<LabelEntry> {
        val corpus = SyntheticCorpus()
        val random = Random(4242)
        val entries = mutableListOf<LabelEntry>()
        repeat(files) { index ->
            val speech = corpus.speech(random, sampleRate * 10).also { SignalTools.normalizeTo(it, -21f) }
            val bed = corpus.melodicBed(random, sampleRate * 10).also { SignalTools.normalizeTo(it, -33f) }
            SignalTools.mixInto(speech, bed, 1f)
            val music = corpus.melodicBed(random, sampleRate * 10).also { SignalTools.normalizeTo(it, -16f) }
            val effects = corpus.effects(random, sampleRate * 10).also { SignalTools.normalizeTo(it, -20f) }

            val file = File(folder.root, "$source/episode$index.wav")
            WavIo.writeMono(file, speech + music + effects, sampleRate)
            entries += LabelEntry(file, 0.5f, 9.5f, AudioClass.SPEECH, source)
            entries += LabelEntry(file, 10.5f, 19.5f, AudioClass.MUSIC, source)
            entries += LabelEntry(file, 20.5f, 29.5f, AudioClass.EFFECTS, source)
        }
        return entries
    }

    @Test
    fun `fine tuning on labelled recordings reports held out accuracy per source`() {
        val entries = buildCorpusOnDisk(files = 6, source = "ary") +
            buildCorpusOnDisk(files = 4, source = "humtv")

        val result = ModelTrainer.run(
            TrainingPlan(
                trainClips = 60,
                validationClips = 30,
                epochs = 12,
                realData = entries,
                warmStart = MlpAudioClassifier.bundledModel(),
                learningRate = 0.002f,
            ),
            log = { },
        )

        assertTrue("no real accuracy was reported", !result.realFrameAccuracy.isNaN())
        assertTrue("real accuracy ${result.realFrameAccuracy}", result.realFrameAccuracy > 0.7f)
        assertTrue("the bundled baseline was not measured", !result.baselineRealFrameAccuracy.isNaN())
        assertTrue("per-source accuracy missing: ${result.realPerSource}", result.realPerSource.isNotEmpty())

        // Fine-tuning must not destroy what the synthetic corpus taught.
        assertTrue("synthetic accuracy collapsed to ${result.frameAccuracy}", result.frameAccuracy > 0.85f)

        val report = result.report()
        assertTrue(report, report.contains("real held-out"))
        assertTrue(report, report.contains("bundled model on the same audio"))
    }

    @Test
    fun `a manifest on disk drives the same training run`() {
        val entries = buildCorpusOnDisk(files = 5, source = "humtv")
        val manifest = File(folder.root, "labels.csv")
        LabelManifest.write(manifest, entries)

        val result = ModelTrainer.run(
            TrainingPlan(
                trainClips = 30,
                validationClips = 15,
                epochs = 8,
                realData = LabelManifest.read(manifest),
                warmStart = MlpAudioClassifier.bundledModel(),
                learningRate = 0.002f,
            ),
            log = { },
        )
        assertEquals(setOf("humtv"), result.realPerSource.keys)
        assertTrue(!result.realFrameAccuracy.isNaN())
    }

    @Test
    fun `a single file leaves nothing to hold out and says so by reporting no real accuracy`() {
        val result = ModelTrainer.run(
            TrainingPlan(
                trainClips = 30,
                validationClips = 15,
                epochs = 5,
                realData = buildCorpusOnDisk(files = 1, source = "ary"),
                warmStart = MlpAudioClassifier.bundledModel(),
            ),
            log = { },
        )
        // One file cannot be both trained on and held out; it is trained on.
        assertTrue(result.realFrameAccuracy.isNaN())
        assertTrue(result.frameAccuracy > 0.8f)
    }
}
