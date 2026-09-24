package io.whozoss.agentos.sdk.spi

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
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
 * and logged by the caller so they can never break case execution.
 */
interface CaseLifecycleObserver : ExtensionPoint {
    /**
     * Called after a case status transition has been resolved.
     *
     * @param caseId the case whose status changed.
     * @param oldStatus the status before the transition.
     * @param newStatus the status after the transition.
     */
    fun onStatusChanged(
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
    fun onEventStored(
        caseId: UUID,
        event: CaseEvent,
    ) {}
}
