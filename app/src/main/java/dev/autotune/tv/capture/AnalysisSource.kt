package dev.autotune.tv.capture

import java.io.Closeable

/**
 * Where the analysis audio comes from.
 *
 * Android gives an ordinary app three ways to hear what the TV is playing, none
 * of which works everywhere:
 *
 *  - [PLAYBACK_CAPTURE] - `AudioPlaybackCapture` (API 29+). Clean and exact, but
 *    apps can opt out and the big streaming services do, so it typically works
 *    for YouTube, browsers and local players and not for Netflix or Prime Video.
 *  - [OUTPUT_MIX] - a `Visualizer` on session 0. Sees everything including DRM
 *    content, but reading the global mix needs `CAPTURE_AUDIO_OUTPUT`, which is
 *    only held by a privileged/system install.
 *  - [MICROPHONE] - listen to the room. Universal on devices that have a mic,
 *    at the cost of room acoustics and a feedback path.
 *
 * When none is available the service still runs: the output processing keeps
 * working with a fixed dialogue preset, it just stops adapting.
 */
enum class SourceKind {
    PLAYBACK_CAPTURE,
    OUTPUT_MIX,
    MICROPHONE,
}

interface AnalysisSource : Closeable {

    val kind: SourceKind

    /** Short human-readable name for the status screen and the notification. */
    val label: String

    /**
     * True when the capture path includes the app's own output processing - the
     * microphone hears the gain Autotune has already applied, so the engine has
     * to subtract it back out to estimate the source level.
     */
    val isClosedLoop: Boolean get() = kind == SourceKind.MICROPHONE

    /**
     * Blocking read of mono samples at the analysis rate into [out].
     * Returns the number of samples written, or -1 when the source has failed.
     */
    fun read(out: FloatArray): Int
}
