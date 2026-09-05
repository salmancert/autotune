package dev.autotune.core.features

/**
 * Layout of the vector handed to the classifier.
 *
 * Instantaneous spectra alone cannot tell speech from music - a sustained vowel
 * and a sustained cello note look alike for 30 ms. The discriminating evidence
 * is temporal: how the level fluctuates, how often the signal falls silent, how
 * strongly the envelope beats at the syllabic rate versus the musical pulse.
 * Every entry below is therefore a statistic over a ~2 s context window.
 */
object FeatureVector {

    const val LEVEL_MEAN = 0
    const val LEVEL_STD = 1
    const val LOW_ENERGY_RATIO = 2
    const val CENTROID_MEAN = 3
    const val CENTROID_STD = 4
    const val SPREAD_MEAN = 5
    const val ROLLOFF_MEAN = 6
    const val ROLLOFF_STD = 7
    const val FLATNESS_MEAN = 8
    const val FLATNESS_STD = 9
    const val FLUX_MEAN = 10
    const val FLUX_STD = 11
    const val ENTROPY_MEAN = 12
    const val ZCR_MEAN = 13
    const val ZCR_STD = 14
    const val BAND_LOW_MEAN = 15
    const val BAND_DIALOGUE_MEAN = 16
    const val BAND_HIGH_MEAN = 17
    const val HARMONICITY_MEAN = 18
    const val HARMONICITY_STD = 19
    const val VOICED_RATIO = 20
    const val PITCH_MEAN = 21
    const val PITCH_STD = 22
    const val MODULATION_4HZ = 23
    const val MODULATION_2HZ = 24
    const val PULSE_STRENGTH = 25

    const val SIZE = 26

    val NAMES: Array<String> = arrayOf(
        "levelMean", "levelStd", "lowEnergyRatio",
        "centroidMean", "centroidStd", "spreadMean",
        "rolloffMean", "rolloffStd", "flatnessMean", "flatnessStd",
        "fluxMean", "fluxStd", "entropyMean",
        "zcrMean", "zcrStd",
        "bandLowMean", "bandDialogueMean", "bandHighMean",
        "harmonicityMean", "harmonicityStd", "voicedRatio",
        "pitchMean", "pitchStd",
        "modulation4Hz", "modulation2Hz", "pulseStrength",
    )
}
