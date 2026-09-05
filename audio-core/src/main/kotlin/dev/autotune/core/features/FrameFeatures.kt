package dev.autotune.core.features

/** Per-frame measurements, reused across frames to keep the analysis loop allocation-free. */
class FrameFeatures {
    /** Frame level, dBFS relative to full-scale sine. */
    var rmsDb: Float = -120f

    /** Spectral centroid expressed as log2(Hz), a stable "brightness" axis. */
    var centroidLog: Float = 0f

    /** Spectral spread around the centroid, in octaves. */
    var spreadOctaves: Float = 0f

    /** Frequency below which 85% of the energy sits, as a fraction of Nyquist. */
    var rolloff85: Float = 0f

    /** Log spectral flatness: near 0 for noise, strongly negative for tonal content. */
    var flatnessLog: Float = 0f

    /** Positive spectral flux against the previous frame (onset strength). */
    var flux: Float = 0f

    /** Normalised spectral entropy in `[0, 1]`. */
    var entropy: Float = 0f

    /** Zero-crossing rate in `[0, 1]`. */
    var zcr: Float = 0f

    /** Energy fraction below 300 Hz (rumble, bass lines, explosions). */
    var bandLow: Float = 0f

    /** Energy fraction in the 300-3400 Hz speech band. */
    var bandDialogue: Float = 0f

    /** Energy fraction above 6 kHz (cymbals, sibilance, air). */
    var bandHigh: Float = 0f

    /** Peak of the normalised autocorrelation over plausible voice/instrument pitches. */
    var harmonicity: Float = 0f

    /** log2 of the detected f0 in Hz, or 0 when the frame is unvoiced. */
    var pitchLog: Float = 0f

    fun copyFrom(other: FrameFeatures) {
        rmsDb = other.rmsDb
        centroidLog = other.centroidLog
        spreadOctaves = other.spreadOctaves
        rolloff85 = other.rolloff85
        flatnessLog = other.flatnessLog
        flux = other.flux
        entropy = other.entropy
        zcr = other.zcr
        bandLow = other.bandLow
        bandDialogue = other.bandDialogue
        bandHigh = other.bandHigh
        harmonicity = other.harmonicity
        pitchLog = other.pitchLog
    }
}
