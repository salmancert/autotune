package dev.autotune.training.data

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.training.Clip
import java.io.File

/**
 * Turns a label manifest into training clips.
 *
 * Files are decoded lazily and one at a time - an evening of drama episodes is
 * gigabytes of PCM, and there is no reason for more than one file to be in
 * memory at once. Entries are grouped by file so that every segment cut from
 * one episode carries the same group id, which is what keeps a train/test split
 * from leaking the same scene into both halves.
 */
object RealCorpus {

    fun load(
        entries: List<LabelEntry>,
        format: AnalysisFormat = AnalysisFormat.DEFAULT,
        minSegmentSeconds: Float = 2.5f,
        onSkip: (String) -> Unit = {},
    ): Sequence<Clip> = sequence {
        val byFile = entries.groupBy { it.file.absolutePath }
        for ((path, fileEntries) in byFile) {
            val file = File(path)
            val decoded = try {
                WavIo.read(file, format.sampleRate)
            } catch (error: Exception) {
                onSkip("${file.name}: ${error.message}")
                continue
            }

            for (entry in fileEntries) {
                val startSample = (entry.start * format.sampleRate).toInt().coerceIn(0, decoded.samples.size)
                val endSample = if (entry.end == Float.MAX_VALUE) {
                    decoded.samples.size
                } else {
                    (entry.end * format.sampleRate).toInt().coerceIn(startSample, decoded.samples.size)
                }
                val length = endSample - startSample
                // Anything shorter than the context window cannot produce a
                // single usable feature vector, so it is not training data.
                if (length < minSegmentSeconds * format.sampleRate) {
                    onSkip("${file.name} [${entry.start}-${entry.end}]: shorter than ${minSegmentSeconds}s")
                    continue
                }
                yield(
                    Clip(
                        samples = decoded.samples.copyOfRange(startSample, endSample),
                        label = entry.label,
                        description = "${file.nameWithoutExtension}@${entry.start}",
                        source = entry.source,
                        groupId = file.nameWithoutExtension,
                    ),
                )
            }
        }
    }

    /** Total labelled seconds per class, for the "is this enough data?" question. */
    fun summarise(entries: List<LabelEntry>, format: AnalysisFormat = AnalysisFormat.DEFAULT): String {
        val bySource = entries.groupBy { it.source }
        return buildString {
            for ((source, sourceEntries) in bySource.entries.sortedBy { it.key }) {
                appendLine("  $source: ${sourceEntries.size} segments across ${sourceEntries.distinctBy { it.file }.size} files")
                for ((label, labelled) in sourceEntries.groupBy { it.label }.entries.sortedBy { it.key.ordinal }) {
                    val seconds = labelled.sumOf { entry ->
                        val duration = entry.durationSeconds
                        if (duration == Float.MAX_VALUE) {
                            runCatching { WavIo.read(entry.file, format.sampleRate).durationSeconds }.getOrDefault(0f)
                                .toDouble()
                        } else {
                            duration.toDouble()
                        }
                    }
                    appendLine("    ${label.name.lowercase().padEnd(8)} %6.1f s".format(seconds))
                }
            }
        }
    }
}
