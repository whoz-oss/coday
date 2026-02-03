package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.orchestration.CaseEventEmitter
import io.whozoss.agentos.sdk.model.CaseEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory

/**
 * Default implementation of CaseEventEmitter.
 * Provides hot observable event emission with buffering.
 * Does NOT handle storage - that's the responsibility of a separate persistence layer.
 */
class DefaultCaseEventEmitter : CaseEventEmitter {
    private val logger = LoggerFactory.getLogger(DefaultCaseEventEmitter::class.java)

    // Hot observable for case events
    private val _events =
        MutableSharedFlow<CaseEvent>(
            replay = 0, // No replay - reconnection handled separately
            extraBufferCapacity = 100, // Buffer for slow consumers
            onBufferOverflow = BufferOverflow.DROP_OLDEST, // Never block the case
        )

    override val events: SharedFlow<CaseEvent> = _events.asSharedFlow()

    /**
     * Emit an event to all collectors.
     * Non-blocking - uses tryEmit to avoid suspending the case thread.
     */
    override fun emit(event: CaseEvent) {
        logger.debug("[Case ${event.caseId}] Emitting event: ${event::class.simpleName}")
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            logger.warn("[Case ${event.caseId}] Event dropped due to buffer overflow: ${event::class.simpleName}")
        }
    }
}
