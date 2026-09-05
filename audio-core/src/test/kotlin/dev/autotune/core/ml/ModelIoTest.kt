package dev.autotune.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ModelIoTest {

    private fun sampleModel(): Mlp = Mlp(
        featureMean = FloatArray(3) { it.toFloat() },
        featureScale = floatArrayOf(1f, 2f, 0.5f),
        layers = listOf(
            DenseLayer(3, 2, floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f, 0.5f, -0.6f), floatArrayOf(0.05f, -0.05f), Activation.TANH),
            DenseLayer(2, 3, floatArrayOf(1f, -1f, 0.5f, 0.25f, -0.75f, 0.125f), floatArrayOf(0f, 0.1f, -0.1f), Activation.SOFTMAX),
        ),
        classNames = listOf("speech", "music", "effects"),
    )

    @Test
    fun `a model survives a write and read round trip`() {
        val original = sampleModel()
        val restored = ModelIo.read(StringReader(ModelIo.write(original)))

        val input = floatArrayOf(0.7f, -1.3f, 2.2f)
        val expected = FloatArray(3).also { original.predict(input, it) }
        val actual = FloatArray(3).also { restored.predict(input, it) }
        for (i in expected.indices) {
            assertEquals(expected[i].toDouble(), actual[i].toDouble(), 1e-5)
        }
        assertEquals(original.classNames, restored.classNames)
    }

    @Test
    fun `a truncated file fails loudly rather than loading garbage`() {
        val text = ModelIo.write(sampleModel()).lines().take(4).joinToString("\n")
        val error = runCatching { ModelIo.read(StringReader(text)) }.exceptionOrNull()
        assertTrue("expected a failure, got $error", error != null)
    }

    @Test
    fun `the bundled model loads and produces a distribution`() {
        val classifier = MlpAudioClassifier.bundled()
        val scores = ClassScores()
        classifier.classify(FloatArray(dev.autotune.core.features.FeatureVector.SIZE), scores)
        val total = scores.probabilities.sum()
        assertEquals(1.0, total.toDouble(), 1e-4)
        for (probability in scores.probabilities) {
            assertTrue("probability $probability out of range", probability in 0f..1f)
        }
    }

    @Test
    fun `softmax output is invariant to a constant offset in the logits`() {
        val layer = DenseLayer(1, 3, floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 1f, 2f), Activation.SOFTMAX)
        val a = FloatArray(3).also { layer.forward(floatArrayOf(0f), it) }
        val b = FloatArray(3).also { layer.forward(floatArrayOf(100f), it) }
        for (i in a.indices) assertEquals(a[i].toDouble(), b[i].toDouble(), 1e-6)
    }
}
