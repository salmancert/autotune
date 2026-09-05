package dev.autotune.core.ml

import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader

/**
 * Plain-text serialisation for [Mlp].
 *
 * A text format keeps the trained weights reviewable in a diff and readable
 * without tooling, which matters more here than the handful of kilobytes a
 * binary format would save.
 *
 * ```
 * autotune-model 1
 * classes speech music effects
 * mean <inputSize floats>
 * scale <inputSize floats>
 * layer <inputs> <outputs> <activation>
 * weights <inputs*outputs floats>
 * bias <outputs floats>
 * ...
 * ```
 */
object ModelIo {

    const val MAGIC = "autotune-model"
    const val VERSION = 1

    fun read(stream: InputStream): Mlp = stream.bufferedReader().use { read(it) }

    fun read(reader: Reader): Mlp {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        val tokens = ArrayDeque<String>()
        buffered.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line -> line.split(' ', '\t').filter(String::isNotEmpty).forEach(tokens::addLast) }

        fun next(): String = tokens.removeFirstOrNull() ?: error("truncated model file")
        fun nextFloat(): Float = next().toFloatOrNull() ?: error("malformed number in model file")
        fun nextInt(): Int = next().toIntOrNull() ?: error("malformed integer in model file")
        fun expect(keyword: String) {
            val token = next()
            check(token == keyword) { "expected '$keyword' in model file but found '$token'" }
        }

        expect(MAGIC)
        val version = nextInt()
        check(version == VERSION) { "unsupported model version $version (this build reads $VERSION)" }

        expect("classes")
        val classCount = nextInt()
        val classNames = List(classCount) { next() }

        expect("mean")
        val inputSize = nextInt()
        val mean = FloatArray(inputSize) { nextFloat() }
        expect("scale")
        val scaleSize = nextInt()
        check(scaleSize == inputSize) { "mean declares $inputSize features but scale declares $scaleSize" }
        val scale = FloatArray(inputSize) { nextFloat() }

        val layers = mutableListOf<DenseLayer>()
        while (tokens.isNotEmpty()) {
            expect("layer")
            val inputs = nextInt()
            val outputs = nextInt()
            val activation = Activation.valueOf(next().uppercase())
            expect("weights")
            val weights = FloatArray(inputs * outputs) { nextFloat() }
            expect("bias")
            val bias = FloatArray(outputs) { nextFloat() }
            layers += DenseLayer(inputs, outputs, weights, bias, activation)
        }
        check(layers.isNotEmpty()) { "model file declares no layers" }
        return Mlp(mean, scale, layers, classNames)
    }

    fun write(model: Mlp): String = buildString {
        appendLine("# Trained by :model-training:trainModel - edit the trainer, not this file.")
        appendLine("$MAGIC $VERSION")
        appendLine("classes ${model.classNames.size} ${model.classNames.joinToString(" ")}")
        appendLine("mean ${model.inputSize}")
        appendLine(model.featureMean.joinToString(" ") { format(it) })
        appendLine("scale ${model.inputSize}")
        appendLine(model.featureScale.joinToString(" ") { format(it) })
        for (layer in model.layers) {
            appendLine("layer ${layer.inputs} ${layer.outputs} ${layer.activation.name.lowercase()}")
            appendLine("weights")
            layer.weights.asSequence().chunked(layer.inputs).forEach { row ->
                appendLine(row.joinToString(" ") { format(it) })
            }
            appendLine("bias")
            appendLine(layer.bias.joinToString(" ") { format(it) })
        }
    }

    private fun format(value: Float): String = String.format(java.util.Locale.ROOT, "%.6g", value)
}
