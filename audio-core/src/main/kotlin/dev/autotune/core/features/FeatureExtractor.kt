package dev.autotune.core.features

/**
 * Framing front end: takes arbitrary-sized blocks of mono audio at the analysis
 * rate and emits one feature snapshot per hop.
 */
class FeatureExtractor(
    val format: AnalysisFormat = AnalysisFormat.DEFAULT,
    contextFrames: Int = (2.0f * format.frameRate).toInt(),
) {
    private val analyzer = FrameAnalyzer(format)
    private val context = ContextWindow(format, contextFrames)

    private val frame = FloatArray(format.frameSize)
    private var filled = 0

    /** Measurements for the most recently completed frame. */
    val frameFeatures = FrameFeatures()

    /** Context summary aligned with [frameFeatures]. */
    val contextVector = FloatArray(FeatureVector.SIZE)

    val isPrimed: Boolean get() = context.isPrimed

    /**
     * Consumes `samples[0, length)` and invokes [onSnapshot] once per completed
     * hop. The callback must not retain the arrays it is handed.
     */
    inline fun process(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
        onSnapshot: (FrameFeatures, FloatArray) -> Unit,
    ) {
        val end = offset + length
        var position = offset
        while (position < end) {
            position += fill(samples, position, end)
            if (isFrameReady()) {
                completeFrame()
                onSnapshot(frameFeatures, contextVector)
            }
        }
    }

    /** Copies as much of `samples[offset, end)` as the current frame still needs. */
    @PublishedApi
    internal fun fill(samples: FloatArray, offset: Int, end: Int): Int {
        val needed = format.frameSize - filled
        val available = end - offset
        val take = if (available < needed) available else needed
        samples.copyInto(frame, filled, offset, offset + take)
        filled += take
        return take
    }

    @PublishedApi
    internal fun isFrameReady(): Boolean = filled == format.frameSize

    @PublishedApi
    internal fun completeFrame() {
        analyzer.analyze(frame, frameFeatures)
        context.add(frameFeatures)
        context.snapshot(contextVector)
        // Slide by the hop; the overlap is what gives the STFT its time resolution.
        val hop = format.hopSize
        if (hop < format.frameSize) {
            frame.copyInto(frame, 0, hop, format.frameSize)
            filled = format.frameSize - hop
        } else {
            filled = 0
        }
    }

    fun reset() {
        analyzer.reset()
        context.reset()
        filled = 0
        java.util.Arrays.fill(contextVector, 0f)
    }
}
