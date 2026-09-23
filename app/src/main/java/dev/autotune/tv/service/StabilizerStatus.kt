package dev.autotune.tv.service

import dev.autotune.core.engine.StabilizerState
import dev.autotune.tv.capture.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Snapshot of what the service is doing, for the status screen. */
data class StabilizerStatus(
    val running: Boolean = false,
    val sourceKind: SourceKind? = null,
    val sourceLabel: String? = null,
    val processorLabel: String? = null,
    val playingPackage: String? = null,
    val bypassed: Boolean = false,

    /** When capture last started, so "has it ever heard anything" is answerable. */
    val captureStartedAtMs: Long = 0L,

    /** Last moment the engine saw audio above the noise floor. */
    val lastSignalAtMs: Long = 0L,

    val state: StabilizerState = StabilizerState(),
) {
    /** True when the engine is adapting rather than sitting on the fixed preset. */
    val isAdaptive: Boolean get() = running && sourceKind != null
}

/**
 * Single place the service publishes to and the UI observes.
 *
 * A `StateFlow` rather than a bound service: the UI only ever reads, and this
 * survives the activity being recreated when the user leaves and comes back.
 */
object StabilizerStatusBus {
    private val mutable = MutableStateFlow(StabilizerStatus())
    val status: StateFlow<StabilizerStatus> = mutable

    fun update(transform: (StabilizerStatus) -> StabilizerStatus) {
        mutable.value = transform(mutable.value)
    }

    fun publishState(state: StabilizerState) {
        val current = mutable.value
        mutable.value = current.copy(
            state = state,
            lastSignalAtMs = if (state.hasSignal) System.currentTimeMillis() else current.lastSignalAtMs,
        )
    }

    fun clear() {
        mutable.value = StabilizerStatus()
    }
}
