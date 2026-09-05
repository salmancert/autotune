package dev.autotune.training.data

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.Mlp
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.core.ml.ModelIo
import dev.autotune.training.DatasetBuilder
import java.io.File
import kotlin.math.max

/**
 * Scores a model against labelled recordings.
 *
 * The point of this tool is the comparison: run it with `--model=` pointing at a
 * newly trained file and without, and the difference tells you whether training
 * on your own channels actually bought anything on your own channels.
 *
 * ```
 * evaluateModel <manifest.csv|directory> [--model=path]
 * ```
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    fun flag(name: String): String? = flags.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

    val input = File(positional.firstOrNull() ?: error("usage: evaluateModel <manifest.csv|directory> [--model=path]"))
    val entries = when {
        input.isDirectory -> LabelManifest.fromDirectoryLayout(input)
        input.isFile -> LabelManifest.read(input)
        else -> error("not found: $input")
    }
    check(entries.isNotEmpty()) { "no labelled audio found at $input" }

    val modelPath = flag("model")
    val model: Mlp = modelPath?.let { ModelIo.read(File(it).inputStream()) } ?: MlpAudioClassifier.bundledModel()
    println("model: ${modelPath ?: "bundled"}")

    val dataset = DatasetBuilder.build(RealCorpus.load(entries, AnalysisFormat.DEFAULT) { println("skipped $it") })
    check(dataset.size > 0) { "no usable feature rows - are the segments long enough?" }

    val probabilities = FloatArray(AudioClass.COUNT)
    val confusion = Array(AudioClass.COUNT) { IntArray(AudioClass.COUNT) }
    val perSourceCorrect = mutableMapOf<String, Int>()
    val perSourceTotal = mutableMapOf<String, Int>()
    var correct = 0

    for (i in 0 until dataset.size) {
        model.predict(dataset.features[i], probabilities)
        var predicted = 0
        for (c in 1 until probabilities.size) if (probabilities[c] > probabilities[predicted]) predicted = c
        confusion[dataset.labels[i]][predicted]++
        val source = dataset.sources[i]
        perSourceTotal[source] = (perSourceTotal[source] ?: 0) + 1
        if (predicted == dataset.labels[i]) {
            correct++
            perSourceCorrect[source] = (perSourceCorrect[source] ?: 0) + 1
        }
    }

    println("frame accuracy: %.3f over ${dataset.size} frames".format(correct.toFloat() / dataset.size))
    for ((source, total) in perSourceTotal.entries.sortedBy { it.key }) {
        println("  %-16s %.3f (%d frames)".format(source, (perSourceCorrect[source] ?: 0).toFloat() / total, total))
    }
    println("confusion (rows = truth, columns = predicted):")
    println("            " + AudioClass.ORDERED.joinToString("  ") { it.name.padStart(8) })
    for (truth in AudioClass.ORDERED) {
        val row = confusion[truth.ordinal]
        val total = max(1, row.sum())
        println(
            truth.name.padEnd(10) + "  " + row.joinToString("  ") {
                "%8s".format("%d (%.0f%%)".format(it, 100f * it / total))
            },
        )
    }
}
