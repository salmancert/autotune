package dev.autotune.training

import dev.autotune.core.features.FeatureVector
import dev.autotune.core.ml.AudioClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the training path working, without paying for a full run. */
class ModelTrainerTest {

    @Test
    fun `a short run learns something and exports a usable model`() {
        val result = ModelTrainer.run(
            TrainingPlan(trainClips = 60, validationClips = 30, epochs = 15),
            log = { },
        )
        assertEquals(FeatureVector.SIZE, result.model.inputSize)
        assertEquals(AudioClass.COUNT, result.model.outputSize)
        assertTrue("accuracy ${result.frameAccuracy} is no better than guessing", result.frameAccuracy > 0.6f)

        val probabilities = FloatArray(AudioClass.COUNT)
        result.model.predict(FloatArray(FeatureVector.SIZE), probabilities)
        assertEquals(1.0, probabilities.sum().toDouble(), 1e-4)
    }

    @Test
    fun `the corpus is balanced and reproducible from its seed`() {
        val first = SyntheticCorpus().generate(seed = 11, clips = 30)
        val second = SyntheticCorpus().generate(seed = 11, clips = 30)
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].label, second[i].label)
            assertTrue("clip $i differs between runs", first[i].samples.contentEquals(second[i].samples))
        }
        for (label in AudioClass.ORDERED) {
            assertEquals(10, first.count { it.label == label })
        }
    }
}
