package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.orchestration.CaseEventEmitter
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import mu.KLogging

/**
 * Default implementation of CaseEventEmitter.
 * Provides hot observable event emission with buffering.
 * Does NOT handle storage - that's the responsibility of a separate persistence layer.
 */
class DefaultCaseEventEmitter : CaseEventEmitter {
    // Hot observable for case events
    private val _events =
        MutableSharedFlow<CaseEvent>(
            replay = 0, // No replay - reconnection handled separately
            extraBufferCapacity = 100, // Buffer for slow consumers
            // tryEmit remains non-blocking. Unlike DROP_OLDEST, SUSPEND makes a full
            // buffer observable instead of silently discarding an event.
        )

    private val _deliveryFailureCount = MutableStateFlow(0L)

    override val events: SharedFlow<CaseEvent> = _events.asSharedFlow()

    /** Monotonic signal that a non-blocking emission could not be delivered. */
    val deliveryFailureCount: StateFlow<Long> = _deliveryFailureCount.asStateFlow()

    /**
     * Current number of active collectors on this flow.
     * Exposed as a [StateFlow] so callers can suspend until at least N subscribers
     * are registered — useful for tests that need a deterministic subscription barrier
     * before emitting on a hot flow with replay=0.
     */
    val subscriptionCount: StateFlow<Int> get() = _events.subscriptionCount

    /**
     * Emit an event to all collectors.
     * Non-blocking - uses tryEmit to avoid suspending the case thread.
     */
    override fun emit(event: CaseEvent) {
        logger.debug { "[Case ${event.caseId}] Emitting event: ${event::class.simpleName}" }
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            _deliveryFailureCount.value += 1
            logger.warn { "[Case ${event.caseId}] Event could not enter the live buffer: ${event::class.simpleName}" }
        }
    }

    companion object : KLogging()
}
