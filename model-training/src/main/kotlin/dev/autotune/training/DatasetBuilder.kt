package dev.autotune.training

import dev.autotune.core.features.AnalysisFormat
import dev.autotune.core.features.FeatureExtractor
import dev.autotune.core.features.FeatureVector
import dev.autotune.core.ml.AudioClass

/** Feature rows with the clip each came from, so splits never leak across a clip. */
class Dataset(
    val features: MutableList<FloatArray> = mutableListOf(),
    val labels: MutableList<Int> = mutableListOf(),
    val clipIds: MutableList<Int> = mutableListOf(),
) {
    val size: Int get() = features.size

    fun add(feature: FloatArray, label: Int, clipId: Int) {
        features += feature
        labels += label
        clipIds += clipId
    }
}

object DatasetBuilder {

    /**
     * Runs the real [FeatureExtractor] over each clip, so training sees exactly
     * the features inference will produce - no separate, drifting Python
     * pipeline to keep in sync.
     *
     * Frames are only kept once the context window is primed, and are decimated
     * so that neighbouring, near-identical windows do not dominate the loss.
     */
    fun build(
        clips: List<Clip>,
        format: AnalysisFormat = AnalysisFormat.DEFAULT,
        keepEvery: Int = 4,
    ): Dataset {
        val dataset = Dataset()
        clips.forEachIndexed { clipId, clip ->
            val extractor = FeatureExtractor(format)
            var frame = 0
            extractor.process(clip.samples, 0, clip.samples.size) { _, features ->
                if (extractor.isPrimed && frame % keepEvery == 0) {
                    dataset.add(features.copyOf(FeatureVector.SIZE), clip.label.ordinal, clipId)
                }
                frame++
            }
        }
        return dataset
    }

    fun classCounts(dataset: Dataset): IntArray {
        val counts = IntArray(AudioClass.COUNT)
        for (label in dataset.labels) counts[label]++
        return counts
    }
}
