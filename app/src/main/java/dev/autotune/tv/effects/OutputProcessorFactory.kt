package dev.autotune.tv.effects

import android.content.Context
import android.os.Build
import android.util.Log
import dev.autotune.core.engine.StabilizerConfig

/** Picks the best output stage the device supports. */
object OutputProcessorFactory {

    private const val TAG = "OutputProcessor"

    /**
     * In order of fidelity: the multiband compressor, then a plain loudness
     * enhancer, then the TV's own volume control.
     *
     * The last one is qualitatively different - coarse, visible, and it moves a
     * control the viewer also owns - so it is only reached when nothing else
     * attached, or when [preferVolumeControl] says the effects are attaching but
     * not actually doing anything, which happens on HDMI passthrough to a
     * soundbar or receiver.
     */
    fun create(
        config: StabilizerConfig,
        context: Context,
        preferVolumeControl: Boolean = false,
    ): AudioOutputProcessor? {
        if (preferVolumeControl) {
            StreamVolumeOutput.create(context)?.let {
                Log.i(TAG, "attached: ${it.label} (at the user's request)")
                return it
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DynamicsProcessorEffect.create(config)?.let {
                Log.i(TAG, "attached: ${it.label}")
                return it
            }
        }
        LoudnessEnhancerEffect.create()?.let {
            Log.i(TAG, "attached: ${it.label}")
            return it
        }

        Log.i(TAG, "no audio effect could attach; falling back to the volume control")
        return StreamVolumeOutput.create(context)
    }
}
