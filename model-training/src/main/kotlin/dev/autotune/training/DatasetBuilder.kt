package dev.autotune.training

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureExtractor
import dev.autotune.core.features.FeatureVector
import dev.autotune.core.ml.AudioClass

/** Feature rows, with the group and source each came from. */
class Dataset {
    val features = mutableListOf<FloatArray>()
    val labels = mutableListOf<Int>()
    val groups = mutableListOf<String>()
    val sources = mutableListOf<String>()

    val size: Int get() = features.size

    fun add(feature: FloatArray, label: Int, group: String, source: String) {
        features += feature
        labels += label
        groups += group
        sources += source
    }

    operator fun plusAssign(other: Dataset) {
        features += other.features
        labels += other.labels
        groups += other.groups
        sources += other.sources
    }

    fun classCounts(): IntArray {
        val counts = IntArray(AudioClass.COUNT)
        for (label in labels) counts[label]++
        return counts
    }

    fun sourceCounts(): Map<String, Int> = sources.groupingBy { it }.eachCount()
}

object DatasetBuilder {

    /**
     * Runs the real [FeatureExtractor] over each clip, so training sees exactly
     * the features inference will produce - no separate, drifting Python
     * pipeline to keep in sync.
     *
     * Frames are only kept once the context window is primed, and are decimated
     * so that neighbouring, near-identical windows do not dominate the loss.
     *
     * Takes a [Sequence] because real corpora are decoded lazily: a few hours of
     * episodes will not fit in memory as PCM, but its feature rows will.
     */
    fun build(
        clips: Sequence<Clip>,
        format: AnalysisFormat = AnalysisFormat.DEFAULT,
        keepEvery: Int = 4,
    ): Dataset {
        val dataset = Dataset()
        for (clip in clips) {
            val extractor = FeatureExtractor(format)
            var frame = 0
            extractor.process(clip.samples, 0, clip.samples.size) { _, features ->
                if (extractor.isPrimed && frame % keepEvery == 0) {
                    dataset.add(features.copyOf(FeatureVector.SIZE), clip.label.ordinal, clip.groupId, clip.source)
                }
                frame++
            }
        }
        return dataset
    }

    fun build(clips: List<Clip>, format: AnalysisFormat = AnalysisFormat.DEFAULT, keepEvery: Int = 4): Dataset =
        build(clips.asSequence(), format, keepEvery)

    fun classCounts(dataset: Dataset): IntArray = dataset.classCounts()
}
