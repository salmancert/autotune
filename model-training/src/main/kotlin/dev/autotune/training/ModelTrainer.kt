package dev.autotune.training

import dev.autotune.core.features.FeatureVector
import dev.autotune.core.ml.Activation
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.DenseLayer
import dev.autotune.core.ml.Mlp
import dev.autotune.core.ml.ModelIo
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Hyper-parameters and corpus size for a training run. */
data class TrainingPlan(
    val trainClips: Int = 900,
    val validationClips: Int = 300,
    val clipSeconds: Float = 4f,
    val trainSeed: Int = 20240607,
    val validationSeed: Int = 99991,
    val initSeed: Int = 12345,
    val hidden: IntArray = intArrayOf(24, 16),
    val epochs: Int = 120,
    val batchSize: Int = 64,
    val learningRate: Float = 0.01f,
    val weightDecay: Float = 1e-4f,
)

class TrainingResult(
    val model: Mlp,
    val frameAccuracy: Float,
    val clipAccuracy: Float,
    val confusion: Array<IntArray>,
) {
    fun report(): String = buildString {
        appendLine("frame accuracy: %.3f".format(frameAccuracy))
        appendLine("clip accuracy:  %.3f".format(clipAccuracy))
        appendLine("confusion (rows = truth, columns = predicted):")
        appendLine("            " + AudioClass.ORDERED.joinToString("  ") { it.name.padStart(8) })
        for (truth in AudioClass.ORDERED) {
            val row = confusion[truth.ordinal]
            val total = max(1, row.sum())
            appendLine(
                truth.name.padEnd(10) + "  " + row.joinToString("  ") {
                    "%8s".format("%d (%.0f%%)".format(it, 100f * it / total))
                },
            )
        }
    }
}

/** Trains the bundled classifier end to end. */
object ModelTrainer {

    fun run(plan: TrainingPlan = TrainingPlan(), log: (String) -> Unit = ::println): TrainingResult {
        val corpus = SyntheticCorpus()

        log("generating ${plan.trainClips} training clips...")
        val trainClips = corpus.generate(plan.trainSeed, plan.trainClips, plan.clipSeconds)
        log("generating ${plan.validationClips} validation clips...")
        val validationClips = corpus.generate(plan.validationSeed, plan.validationClips, plan.clipSeconds)

        log("extracting features...")
        val train = DatasetBuilder.build(trainClips)
        val validation = DatasetBuilder.build(validationClips)
        log("train rows: ${train.size} ${DatasetBuilder.classCounts(train).toList()}")
        log("valid rows: ${validation.size} ${DatasetBuilder.classCounts(validation).toList()}")

        val (mean, scale) = standardisation(train)
        val standardisedTrain = train.features.map { standardise(it, mean, scale) }
        val standardisedValidation = validation.features.map { standardise(it, mean, scale) }

        val sizes = intArrayOf(FeatureVector.SIZE, *plan.hidden.toTypedArray().toIntArray(), AudioClass.COUNT)
        val network = TrainableMlp(sizes, plan.initSeed)

        // Inverse-frequency weighting keeps a slightly uneven corpus from
        // biasing the decision boundary toward the majority class.
        val counts = DatasetBuilder.classCounts(train)
        val weights = FloatArray(AudioClass.COUNT) { train.size.toFloat() / (AudioClass.COUNT * max(1, counts[it])) }

        val order = MutableList(standardisedTrain.size) { it }
        val random = Random(plan.initSeed)
        log("training ${sizes.joinToString("-")} for ${plan.epochs} epochs...")
        for (epoch in 1..plan.epochs) {
            order.shuffle(random)
            var loss = 0f
            var batch = 0
            for ((processed, index) in order.withIndex()) {
                loss += network.accumulate(standardisedTrain[index], train.labels[index], weights[train.labels[index]])
                batch++
                if (batch == plan.batchSize || processed == order.lastIndex) {
                    network.applyGradients(batch, plan.learningRate, plan.weightDecay)
                    batch = 0
                }
            }
            if (epoch % 20 == 0 || epoch == 1) {
                val accuracy = frameAccuracy(network, standardisedValidation, validation.labels)
                log("  epoch %3d  loss %.4f  validation %.3f".format(epoch, loss / order.size, accuracy))
            }
        }

        val confusion = Array(AudioClass.COUNT) { IntArray(AudioClass.COUNT) }
        for (i in standardisedValidation.indices) {
            confusion[validation.labels[i]][network.predictedClass(standardisedValidation[i])]++
        }

        val model = toModel(network, mean, scale)
        return TrainingResult(
            model = model,
            frameAccuracy = frameAccuracy(network, standardisedValidation, validation.labels),
            clipAccuracy = clipAccuracy(network, standardisedValidation, validation),
            confusion = confusion,
        )
    }

    private fun frameAccuracy(network: TrainableMlp, features: List<FloatArray>, labels: List<Int>): Float {
        if (features.isEmpty()) return 0f
        var correct = 0
        for (i in features.indices) {
            if (network.predictedClass(features[i]) == labels[i]) correct++
        }
        return correct.toFloat() / features.size
    }

    /** Majority vote per clip: how the engine behaves once posteriors are smoothed. */
    private fun clipAccuracy(network: TrainableMlp, features: List<FloatArray>, dataset: Dataset): Float {
        val votes = mutableMapOf<Int, IntArray>()
        val truth = mutableMapOf<Int, Int>()
        for (i in features.indices) {
            val clipId = dataset.clipIds[i]
            votes.getOrPut(clipId) { IntArray(AudioClass.COUNT) }[network.predictedClass(features[i])]++
            truth[clipId] = dataset.labels[i]
        }
        if (votes.isEmpty()) return 0f
        var correct = 0
        for ((clipId, tally) in votes) {
            var best = 0
            for (c in 1 until tally.size) if (tally[c] > tally[best]) best = c
            if (best == truth[clipId]) correct++
        }
        return correct.toFloat() / votes.size
    }

    private fun standardisation(dataset: Dataset): Pair<FloatArray, FloatArray> {
        val n = FeatureVector.SIZE
        val mean = FloatArray(n)
        val scale = FloatArray(n)
        val sums = DoubleArray(n)
        val squares = DoubleArray(n)
        for (row in dataset.features) {
            for (i in 0 until n) {
                sums[i] += row[i]
                squares[i] += row[i].toDouble() * row[i]
            }
        }
        val count = max(1, dataset.size)
        for (i in 0 until n) {
            mean[i] = (sums[i] / count).toFloat()
            val variance = squares[i] / count - mean[i].toDouble() * mean[i]
            // A constant feature would otherwise divide by ~0 and explode.
            scale[i] = max(1e-3, sqrt(max(0.0, variance))).toFloat()
        }
        return mean to scale
    }

    private fun standardise(row: FloatArray, mean: FloatArray, scale: FloatArray): FloatArray =
        FloatArray(row.size) { (row[it] - mean[it]) / scale[it] }

    /**
     * The trainer standardises up front, so the exported model folds the same
     * mean/scale into itself and inference needs no extra step.
     */
    private fun toModel(network: TrainableMlp, mean: FloatArray, scale: FloatArray): Mlp {
        val layers = (0 until network.layers).map { l ->
            DenseLayer(
                inputs = network.inputsOf(l),
                outputs = network.outputsOf(l),
                weights = network.weightsOf(l).copyOf(),
                bias = network.biasOf(l).copyOf(),
                activation = if (l == network.layers - 1) Activation.SOFTMAX else Activation.TANH,
            )
        }
        return Mlp(mean, scale, layers, AudioClass.ORDERED.map { it.name.lowercase() })
    }
}

/**
 * `trainModel [outputDir] [--clips=N] [--epochs=N] [--dry-run]`
 *
 * The overrides exist so a CI smoke test can run the whole pipeline in seconds
 * without pretending the result is a shippable model.
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    fun flag(name: String): String? = flags.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

    val outputDirectory = File(positional.firstOrNull() ?: "audio-core/src/main/resources/dev/autotune/core/ml")
    val defaults = TrainingPlan()
    val clips = flag("clips")?.toInt() ?: defaults.trainClips
    val plan = defaults.copy(
        trainClips = clips,
        validationClips = flag("validationClips")?.toInt() ?: maxOf(3, clips / 3),
        epochs = flag("epochs")?.toInt() ?: defaults.epochs,
    )

    val started = System.currentTimeMillis()
    val result = ModelTrainer.run(plan)
    println(result.report())

    if (flags.contains("--dry-run")) {
        println("dry run: model not written")
        return
    }
    outputDirectory.mkdirs()
    val target = File(outputDirectory, "speech_music_mlp.model")
    target.writeText(ModelIo.write(result.model))
    println("wrote ${target.absolutePath} (${target.length()} bytes) in ${(System.currentTimeMillis() - started) / 1000}s")
}
