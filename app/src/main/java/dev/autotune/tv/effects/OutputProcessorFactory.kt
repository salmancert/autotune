package dev.autotune.tv.effects

import android.os.Build
import dev.autotune.core.engine.StabilizerConfig

/** Picks the best output effect the device supports. */
object OutputProcessorFactory {

    fun create(config: StabilizerConfig): AudioOutputProcessor? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DynamicsProcessorEffect.create(config)?.let { return it }
        }
        return LoudnessEnhancerEffect.create()
    }
}
