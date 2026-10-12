package io.whozoss.agentos.sdk.spi

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import kotlinx.coroutines.CancellationException
import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * Generic SPI extension point that observes case lifecycle transitions and event
 * persistence.
 *
 * Observers are notified:
 * - when a case transitions between [CaseStatus] values (see [onStatusChanged]);
 * - when an event has been stored by the case runtime (see [onEventStored]).
 *
 * ### Safe default
 *
 * Both methods default to no-op, so an observer only needs to override the callbacks
 * it cares about. When no observer is registered, notifications are skipped entirely
 * and existing behavior is unchanged.
 *
 * ### Exception handling
 *
 * Observers are notifications only: exceptions thrown by an implementation are caught
 * and logged by the caller so they can never break case execution. [CancellationException]
 * is the exception to that rule and must be rethrown by the caller: an observer is not
 * failing, the scope it runs in is being torn down.
 *
 * ### Suspension
 *
 * Both callbacks are suspending functions. An observer that forwards a transition to an
 * external system must be able to suspend rather than block the thread driving the case,
 * and must remain cancellable.
 *
 * Observers are notified on a best-effort basis: they must not be relied upon to carry
 * state the case itself depends on, and a slow observer must not become a brake on case
 * execution — it is the host's responsibility to decide how notifications are dispatched.
 */
interface CaseLifecycleObserver : ExtensionPoint {
    /**
     * Called after a case status transition has been resolved.
     *
     * @param caseId the case whose status changed.
     * @param oldStatus the status before the transition.
     * @param newStatus the status after the transition.
     */
    suspend fun onStatusChanged(
        caseId: UUID,
        oldStatus: CaseStatus,
        newStatus: CaseStatus,
    ) {}

    /**
     * Called after an event produced by the runtime has been stored.
     *
     * @param caseId the case the event belongs to.
     * @param event the stored event (with a stable id when it is durable).
     */
    suspend fun onEventStored(
        caseId: UUID,
        event: CaseEvent,
    ) {}
}
