package dev.autotune.training

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureVector
import dev.autotune.core.ml.Activation
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.DenseLayer
import dev.autotune.core.ml.Mlp
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.core.ml.ModelIo
import dev.autotune.training.data.LabelEntry
import dev.autotune.training.data.LabelManifest
import dev.autotune.training.data.RealCorpus
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Corpus size, hyper-parameters, and any real recordings to mix in. */
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

    /** Labelled real recordings to mix in with the synthetic corpus. */
    val realData: List<LabelEntry> = emptyList(),

    /**
     * How much each real feature row counts for relative to a synthetic one.
     *
     * A few hours of television is a rounding error next to an hour of
     * synthesis, and would otherwise be averaged away. Weighting it up is the
     * cheap way to let a small, precious corpus actually steer the boundary.
     */
    val realWeight: Float = 4f,

    /** Share of real *files* (never scenes) held out for reporting. */
    val realHoldoutFraction: Float = 0.25f,

    /** Continue from these weights instead of starting over. */
    val warmStart: Mlp? = null,
)

class TrainingResult(
    val model: Mlp,
    val frameAccuracy: Float,
    val clipAccuracy: Float,
    val confusion: Array<IntArray>,
    val realFrameAccuracy: Float = Float.NaN,
    val realConfusion: Array<IntArray>? = null,
    val realPerSource: Map<String, Float> = emptyMap(),
    val baselineRealFrameAccuracy: Float = Float.NaN,
) {
    fun report(): String = buildString {
        appendLine("synthetic held-out")
        appendLine("  frame accuracy: %.3f".format(frameAccuracy))
        appendLine("  clip accuracy:  %.3f".format(clipAccuracy))
        appendLine(confusionTable(confusion, "  "))
        if (!realFrameAccuracy.isNaN()) {
            appendLine("real held-out (unseen files)")
            appendLine("  frame accuracy: %.3f".format(realFrameAccuracy))
            if (!baselineRealFrameAccuracy.isNaN()) {
                val delta = realFrameAccuracy - baselineRealFrameAccuracy
                appendLine(
                    "  bundled model on the same audio: %.3f  (%+.3f)"
                        .format(baselineRealFrameAccuracy, delta),
                )
            }
            for ((source, accuracy) in realPerSource.entries.sortedBy { it.key }) {
                appendLine("  %-16s %.3f".format(source, accuracy))
            }
            realConfusion?.let { appendLine(confusionTable(it, "  ")) }
        }
    }

    private fun confusionTable(matrix: Array<IntArray>, indent: String): String = buildString {
        appendLine("${indent}confusion (rows = truth, columns = predicted):")
        appendLine(indent + "            " + AudioClass.ORDERED.joinToString("  ") { it.name.padStart(8) })
        for (truth in AudioClass.ORDERED) {
            val row = matrix[truth.ordinal]
            val total = max(1, row.sum())
            appendLine(
                indent + truth.name.padEnd(10) + "  " + row.joinToString("  ") {
                    "%8s".format("%d (%.0f%%)".format(it, 100f * it / total))
                },
            )
        }
    }
}

/** Trains the bundled classifier end to end. */
object ModelTrainer {

    private val format = AnalysisFormat.DEFAULT

    fun run(plan: TrainingPlan = TrainingPlan(), log: (String) -> Unit = ::println): TrainingResult {
        val corpus = SyntheticCorpus()

        log("generating ${plan.trainClips} synthetic training clips...")
        val trainClips = corpus.generate(plan.trainSeed, plan.trainClips, plan.clipSeconds)
        log("generating ${plan.validationClips} synthetic validation clips...")
        val validationClips = corpus.generate(plan.validationSeed, plan.validationClips, plan.clipSeconds)

        log("extracting features...")
        val syntheticTrain = DatasetBuilder.build(trainClips)
        val syntheticValidation = DatasetBuilder.build(validationClips)
        log("  synthetic train ${syntheticTrain.size} rows ${syntheticTrain.classCounts().toList()}")
        log("  synthetic valid ${syntheticValidation.size} rows ${syntheticValidation.classCounts().toList()}")

        // Real recordings are split by file, so no episode appears on both sides.
        var realTrain = Dataset()
        var realHoldout = Dataset()
        if (plan.realData.isNotEmpty()) {
            val (trainEntries, holdoutEntries) = splitByFile(plan.realData, plan.realHoldoutFraction)
            log("loading real audio: ${trainEntries.size} training segments, ${holdoutEntries.size} held out...")
            realTrain = DatasetBuilder.build(RealCorpus.load(trainEntries, format) { log("  skipped $it") })
            realHoldout = DatasetBuilder.build(RealCorpus.load(holdoutEntries, format) { log("  skipped $it") })
            log("  real train ${realTrain.size} rows ${realTrain.classCounts().toList()} from ${realTrain.sourceCounts()}")
            log("  real valid ${realHoldout.size} rows ${realHoldout.classCounts().toList()}")
            require(realTrain.size > 0) { "no usable real training rows - are the segments long enough?" }
        }

        val combined = Dataset().apply {
            this += syntheticTrain
            this += realTrain
        }
        val rowWeights = FloatArray(combined.size) { if (it >= syntheticTrain.size) plan.realWeight else 1f }

        // Warm starting only makes sense if the inputs are scaled the way the
        // existing weights expect, so the standardisation comes along with them.
        val warmStart = plan.warmStart
        val mean: FloatArray
        val scale: FloatArray
        if (warmStart != null) {
            mean = warmStart.featureMean.copyOf()
            scale = warmStart.featureScale.copyOf()
            log("warm starting from the bundled model (keeping its standardisation)")
        } else {
            val computed = standardisation(combined)
            mean = computed.first
            scale = computed.second
        }

        val standardisedTrain = combined.features.map { standardise(it, mean, scale) }
        val standardisedSynthetic = syntheticValidation.features.map { standardise(it, mean, scale) }
        val standardisedReal = realHoldout.features.map { standardise(it, mean, scale) }

        val sizes = intArrayOf(FeatureVector.SIZE, *plan.hidden.toTypedArray().toIntArray(), AudioClass.COUNT)
        val network = TrainableMlp(sizes, plan.initSeed)
        warmStart?.let { network.warmStartFrom(it) }

        // Inverse-frequency weighting keeps an uneven corpus - and real audio is
        // always uneven - from biasing the boundary toward the majority class.
        val counts = combined.classCounts()
        val classWeights = FloatArray(AudioClass.COUNT) {
            combined.size.toFloat() / (AudioClass.COUNT * max(1, counts[it]))
        }

        val order = MutableList(standardisedTrain.size) { it }
        val random = Random(plan.initSeed)
        log("training ${sizes.joinToString("-")} on ${order.size} rows for ${plan.epochs} epochs...")
        for (epoch in 1..plan.epochs) {
            order.shuffle(random)
            var loss = 0f
            var batch = 0
            for ((processed, index) in order.withIndex()) {
                val weight = classWeights[combined.labels[index]] * rowWeights[index]
                loss += network.accumulate(standardisedTrain[index], combined.labels[index], weight)
                batch++
                if (batch == plan.batchSize || processed == order.lastIndex) {
                    network.applyGradients(batch, plan.learningRate, plan.weightDecay)
                    batch = 0
                }
            }
            if (epoch % 20 == 0 || epoch == 1) {
                val synthetic = accuracy(network, standardisedSynthetic, syntheticValidation.labels)
                val real = if (standardisedReal.isEmpty()) {
                    ""
                } else {
                    "  real %.3f".format(accuracy(network, standardisedReal, realHoldout.labels))
                }
                log("  epoch %3d  loss %.4f  synthetic %.3f%s".format(epoch, loss / order.size, synthetic, real))
            }
        }

        val model = toModel(network, mean, scale)
        return TrainingResult(
            model = model,
            frameAccuracy = accuracy(network, standardisedSynthetic, syntheticValidation.labels),
            clipAccuracy = groupAccuracy(network, standardisedSynthetic, syntheticValidation),
            confusion = confusion(network, standardisedSynthetic, syntheticValidation.labels),
            realFrameAccuracy = if (standardisedReal.isEmpty()) {
                Float.NaN
            } else {
                accuracy(network, standardisedReal, realHoldout.labels)
            },
            realConfusion = if (standardisedReal.isEmpty()) {
                null
            } else {
                confusion(network, standardisedReal, realHoldout.labels)
            },
            realPerSource = perSourceAccuracy(network, standardisedReal, realHoldout),
            baselineRealFrameAccuracy = if (realHoldout.size == 0) Float.NaN else bundledAccuracy(realHoldout),
        )
    }

    /**
     * Splits on the file, not the segment.
     *
     * Two segments from the same episode share a scene, a mix engineer and a
     * cast; letting one into training and the other into the held-out set would
     * measure memorisation and call it accuracy. The hash makes the assignment
     * stable across runs.
     */
    private fun splitByFile(
        entries: List<LabelEntry>,
        holdoutFraction: Float,
    ): Pair<List<LabelEntry>, List<LabelEntry>> {
        if (holdoutFraction <= 0f) return entries to emptyList()
        val files = entries.map { it.file.nameWithoutExtension }.distinct().sorted()
        val holdoutCount = max(1, (files.size * holdoutFraction).toInt())
        val holdout = files.sortedBy { abs(it.hashCode().toLong()) }.take(holdoutCount).toSet()
        // With only one file there is nothing to hold out; train on it and say so.
        if (holdout.size == files.size) return entries to emptyList()
        return entries.partition { it.file.nameWithoutExtension !in holdout }
    }

    private fun accuracy(network: TrainableMlp, features: List<FloatArray>, labels: List<Int>): Float {
        if (features.isEmpty()) return Float.NaN
        var correct = 0
        for (i in features.indices) if (network.predictedClass(features[i]) == labels[i]) correct++
        return correct.toFloat() / features.size
    }

    private fun confusion(network: TrainableMlp, features: List<FloatArray>, labels: List<Int>): Array<IntArray> {
        val matrix = Array(AudioClass.COUNT) { IntArray(AudioClass.COUNT) }
        for (i in features.indices) matrix[labels[i]][network.predictedClass(features[i])]++
        return matrix
    }

    private fun perSourceAccuracy(
        network: TrainableMlp,
        features: List<FloatArray>,
        dataset: Dataset,
    ): Map<String, Float> {
        if (features.isEmpty()) return emptyMap()
        val correct = mutableMapOf<String, Int>()
        val total = mutableMapOf<String, Int>()
        for (i in features.indices) {
            val source = dataset.sources[i]
            total[source] = (total[source] ?: 0) + 1
            if (network.predictedClass(features[i]) == dataset.labels[i]) {
                correct[source] = (correct[source] ?: 0) + 1
            }
        }
        return total.mapValues { (source, count) -> (correct[source] ?: 0).toFloat() / count }
    }

    /** What the currently shipped model scores on the same audio, for comparison. */
    private fun bundledAccuracy(dataset: Dataset): Float = runCatching {
        val classifier = MlpAudioClassifier.bundled(smoothing = 1f)
        val scores = dev.autotune.core.ml.ClassScores()
        var correct = 0
        for (i in 0 until dataset.size) {
            classifier.reset()
            classifier.classify(dataset.features[i], scores)
            if (scores.argMax.ordinal == dataset.labels[i]) correct++
        }
        correct.toFloat() / dataset.size
    }.getOrDefault(Float.NaN)

    /** Majority vote per group: how the engine behaves once posteriors are smoothed. */
    private fun groupAccuracy(network: TrainableMlp, features: List<FloatArray>, dataset: Dataset): Float {
        if (features.isEmpty()) return Float.NaN
        val votes = mutableMapOf<String, IntArray>()
        val truth = mutableMapOf<String, Int>()
        for (i in features.indices) {
            val group = dataset.groups[i]
            votes.getOrPut(group) { IntArray(AudioClass.COUNT) }[network.predictedClass(features[i])]++
            truth[group] = dataset.labels[i]
        }
        var correct = 0
        for ((group, tally) in votes) {
            var best = 0
            for (c in 1 until tally.size) if (tally[c] > tally[best]) best = c
            if (best == truth[group]) correct++
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
 * ```
 * trainModel [outputDir] [--clips=N] [--epochs=N] [--dry-run]
 *            [--data=<manifest.csv|directory>] [--real-weight=4] [--fine-tune]
 * ```
 *
 * With no `--data` this reproduces the bundled model from the synthetic corpus.
 * With `--data` it mixes in labelled recordings; add `--fine-tune` to continue
 * from the shipped weights instead of starting over, which is what you want for
 * anything under a few hours of audio.
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    fun flag(name: String): String? = flags.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

    val outputDirectory = File(positional.firstOrNull() ?: "audio-core/src/main/resources/dev/autotune/core/ml")
    val defaults = TrainingPlan()
    val clips = flag("clips")?.toInt() ?: defaults.trainClips

    val realData = flag("data")?.let { path ->
        val source = File(path)
        val entries = when {
            source.isDirectory -> LabelManifest.fromDirectoryLayout(source)
            source.isFile -> LabelManifest.read(source)
            else -> error("--data path not found: $path")
        }
        check(entries.isNotEmpty()) { "no labelled audio found at $path" }
        println("labelled audio:")
        print(RealCorpus.summarise(entries))
        entries
    } ?: emptyList()

    val fineTune = flags.contains("--fine-tune")
    if (fineTune && realData.isEmpty()) {
        println("--fine-tune without --data would just re-run the synthetic training; ignoring it.")
    }
    val plan = defaults.copy(
        trainClips = clips,
        validationClips = flag("validationClips")?.toInt() ?: maxOf(3, clips / 3),
        // Fine-tuning needs far fewer passes; it is adjusting a boundary, not
        // discovering one.
        epochs = flag("epochs")?.toInt() ?: if (fineTune) 40 else defaults.epochs,
        realData = realData,
        realWeight = flag("real-weight")?.toFloat() ?: defaults.realWeight,
        realHoldoutFraction = flag("holdout")?.toFloat() ?: defaults.realHoldoutFraction,
        // Smaller steps too, so the run does not forget what the synthetic
        // corpus taught in order to fit two hours of one channel.
        learningRate = flag("learning-rate")?.toFloat() ?: if (fineTune) 0.002f else defaults.learningRate,
        warmStart = if (fineTune && realData.isNotEmpty()) MlpAudioClassifier.bundledModel() else null,
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
