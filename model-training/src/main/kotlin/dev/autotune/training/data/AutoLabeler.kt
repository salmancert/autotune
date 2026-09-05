package dev.autotune.training.data

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureExtractor
import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.ClassScores
import dev.autotune.core.ml.Mlp
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.core.ml.ModelIo
import java.io.File
import java.util.Locale

/**
 * Draft labels for real recordings, so a person only has to correct them.
 *
 * Hand-labelling four hours of drama at second resolution is work nobody
 * finishes, and a corpus nobody finishes is a corpus that never trains
 * anything. The existing model is already right most of the time on obvious
 * material - a title song, a silent room, a two-hander scene - so it drafts the
 * manifest and sorts what it is least sure about to the top of a review list.
 * The human effort goes where the information is.
 */
class AutoLabeler(
    private val model: Mlp,
    private val format: AnalysisFormat = AnalysisFormat.DEFAULT,
    /** Shortest span worth emitting; below this it is a transition, not a scene. */
    private val minSegmentSeconds: Float = 3f,
    /** Frames quieter than this are treated as silence and break a segment. */
    private val silenceDb: Float = -62f,
    /**
     * Trimmed off each end of every segment before it is written.
     *
     * The boundary a classifier reports is never exact - it turns over somewhere
     * inside the transition - so the last half second before a cut is the least
     * trustworthy audio in the file. Throwing it away costs a little data and
     * buys labels that are right where it matters.
     */
    private val edgeTrimSeconds: Float = 0.5f,
) {
    private val classifier = MlpAudioClassifier(model, smoothing = 0.2f)

    /** Per-hop decisions for one file. */
    private class Track(val labels: IntArray, val confidence: FloatArray, val hopSeconds: Float)

    fun label(file: File, source: String): List<LabelEntry> {
        val decoded = WavIo.read(file, format.sampleRate)
        val track = classify(decoded.samples)
        return segment(track, file, source)
    }

    private fun classify(samples: FloatArray): Track {
        val extractor = FeatureExtractor(format)
        classifier.reset()
        val scores = ClassScores()
        val labels = mutableListOf<Int>()
        val confidence = mutableListOf<Float>()

        extractor.process(samples, 0, samples.size) { frame, features ->
            if (!extractor.isPrimed) return@process
            if (frame.rmsDb < silenceDb) {
                labels += SILENCE
                confidence += 1f
            } else {
                classifier.classify(features, scores)
                labels += scores.argMax.ordinal
                confidence += scores.probabilities[scores.argMax.ordinal]
            }
        }
        smoothLabels(labels)
        return Track(labels.toIntArray(), confidence.toFloatArray(), format.hopDurationMs / 1000f)
    }

    /**
     * Median filter over ~1 s of decisions.
     *
     * Raw per-hop labels flicker at every syllable boundary; without this the
     * manifest would be thousands of half-second segments instead of the scenes
     * a person can actually check.
     */
    private fun smoothLabels(labels: MutableList<Int>) {
        val radius = (format.frameRate / 2).toInt().coerceAtLeast(1)
        val original = labels.toIntArray()
        val counts = IntArray(AudioClass.COUNT + 1)
        for (i in labels.indices) {
            java.util.Arrays.fill(counts, 0)
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(original.size - 1)
            for (j in from..to) counts[indexOf(original[j])]++
            var best = 0
            for (c in counts.indices) if (counts[c] > counts[best]) best = c
            labels[i] = if (best == AudioClass.COUNT) SILENCE else best
        }
    }

    private fun indexOf(label: Int): Int = if (label == SILENCE) AudioClass.COUNT else label

    private fun segment(track: Track, file: File, source: String): List<LabelEntry> {
        val entries = mutableListOf<LabelEntry>()
        val minFrames = (minSegmentSeconds / track.hopSeconds).toInt().coerceAtLeast(1)

        var start = 0
        while (start < track.labels.size) {
            val label = track.labels[start]
            var end = start
            while (end + 1 < track.labels.size && track.labels[end + 1] == label) end++
            val length = end - start + 1

            if (label != SILENCE && length >= minFrames) {
                var sum = 0.0
                for (i in start..end) sum += track.confidence[i]

                // The context window looks *backwards*: a decision at hop i
                // describes the two seconds ending there, so the audio it
                // describes started earlier than i. Without this correction every
                // boundary lands about a second late and each segment begins with
                // a slice of the previous scene - mislabelled training data,
                // exactly where the classes are hardest to tell apart.
                val from = (start * track.hopSeconds - CONTEXT_LAG_SECONDS + edgeTrimSeconds).coerceAtLeast(0f)
                val to = (end + 1) * track.hopSeconds - CONTEXT_LAG_SECONDS - edgeTrimSeconds
                if (to - from >= minSegmentSeconds) {
                    entries += LabelEntry(
                        file = file,
                        start = from,
                        end = to,
                        label = AudioClass.ORDERED[label],
                        source = source,
                        confidence = (sum / length).toFloat(),
                    )
                }
            }
            start = end + 1
        }
        return entries
    }

    companion object {
        private const val SILENCE = -1

        /**
         * Half the 2 s context window: how far behind the audio a decision is,
         * measured as where the window's evidence is centred.
         */
        private const val CONTEXT_LAG_SECONDS = 1f
    }
}

/**
 * ```
 * autoLabel <audioDir|file> [--out=labels.csv] [--source=ary] [--model=path]
 *           [--min-segment=3] [--review=review.csv]
 * ```
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    fun flag(name: String): String? = flags.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

    val input = File(positional.firstOrNull() ?: error("usage: autoLabel <audioDir|file> [--out=labels.csv]"))
    val files = when {
        input.isDirectory -> input.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .sortedBy { it.path }
            .toList()
        input.isFile -> listOf(input)
        else -> error("not found: $input")
    }
    check(files.isNotEmpty()) { "no .wav files under $input (convert downloads with ffmpeg first)" }

    val model = flag("model")?.let { ModelIo.read(File(it).inputStream()) } ?: MlpAudioClassifier.bundledModel()
    val labeler = AutoLabeler(
        model = model,
        minSegmentSeconds = flag("min-segment")?.toFloat() ?: 3f,
    )

    val manifest = File(flag("out") ?: File(input.takeIf { it.isDirectory } ?: input.parentFile, "labels.csv").path)
    val entries = mutableListOf<LabelEntry>()
    for (file in files) {
        // A file in data/ary/... is tagged ary unless told otherwise.
        val source = flag("source") ?: file.parentFile?.name ?: "unknown"
        val labelled = runCatching { labeler.label(file, source) }
            .onFailure { println("skipped ${file.name}: ${it.message}") }
            .getOrDefault(emptyList())
        entries += labelled
        println("${file.name}: ${labelled.size} segments")
    }

    LabelManifest.write(manifest, entries.sortedWith(compareBy({ it.file.path }, { it.start })))
    println("\nwrote ${manifest.absolutePath}")

    val review = File(flag("review") ?: File(manifest.parentFile, "review.csv").path)
    val uncertain = entries.sortedBy { it.confidence }.take(REVIEW_LIMIT)
    LabelManifest.write(review, uncertain)

    println(summary(entries))
    println(
        "check the ${uncertain.size} least certain segments first: ${review.absolutePath}\n" +
            "fix any wrong label in ${manifest.name}, delete rows you are unsure about, then:\n" +
            "  ./gradlew :model-training:trainModel --args=\"--data=${manifest.absolutePath} --fine-tune\"",
    )
}

private const val REVIEW_LIMIT = 60

private fun summary(entries: List<LabelEntry>): String = buildString {
    appendLine("\nlabelled ${entries.size} segments:")
    for (label in AudioClass.ORDERED) {
        val forLabel = entries.filter { it.label == label }
        val seconds = forLabel.sumOf { it.durationSeconds.toDouble() }
        val confident = forLabel.count { it.confidence >= 0.9f }
        appendLine(
            "  %-8s %6.1f s in %3d segments (%d of them above 0.90 confidence)"
                .format(Locale.ROOT, label.name.lowercase(), seconds, forLabel.size, confident),
        )
    }
}
