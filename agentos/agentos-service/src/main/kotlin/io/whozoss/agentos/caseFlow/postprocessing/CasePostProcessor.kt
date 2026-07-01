package io.whozoss.agentos.caseFlow.postprocessing

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.caseEvent.CaseEvent

/**
 * Extension point for post-processing hooks on a [Case].
 *
 * Implementations are discovered via Spring component scanning and invoked by
 * [CasePostProcessingService] whenever a case is touched at a relevant lifecycle point
 * (first user message, subsequent turns, turn completion).
 *
 * Contract:
 * - [shouldProcess] is called first and must be cheap (no I/O). If it returns false,
 *   [process] is not called.
 * - [process] runs inside a fire-and-forget coroutine. It must not throw — exceptions
 *   are caught and logged by the orchestrating service.
 * - [process] receives an [emitEvent] callback to push transient SSE events without
 *   taking a direct dependency on [io.whozoss.agentos.caseFlow.CaseServiceImpl].
 */
interface CasePostProcessor {
    /**
     * Whether this processor wants to run for the given [case] and current [events].
     * Called synchronously on the calling thread — must be side-effect-free.
     */
    fun shouldProcess(
        case: Case,
        events: List<CaseEvent>,
    ): Boolean

    /**
     * Perform the post-processing work.
     *
     * @param case The current persisted state of the case.
     * @param events The full event history at the time of the trigger.
     * @param emitEvent Callback to push a transient [CaseEvent] to SSE subscribers.
     *   The event is not persisted.
     */
    suspend fun process(
        case: Case,
        events: List<CaseEvent>,
        emitEvent: (CaseEvent) -> Unit,
    )
}
