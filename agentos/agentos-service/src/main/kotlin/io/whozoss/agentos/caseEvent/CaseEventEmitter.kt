package io.whozoss.agentos.orchestration

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface for emitting case events.
 * Can be delegated to separate emission concerns from business logic.
 * Focuses solely on event emission (hot observable flow).
 */
interface CaseEventEmitter {
    /**
     * Observable flow of case events.
     * This is a hot flow - events are emitted regardless of collectors.
     */
    val events: SharedFlow<CaseEvent>

    /**
     * Emit an event to all collectors.
     * Non-blocking operation.
     */
    fun emit(event: CaseEvent)
}
