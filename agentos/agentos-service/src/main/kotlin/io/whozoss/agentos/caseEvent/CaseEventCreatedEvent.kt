package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent

/**
 * Published by [CaseEventServiceImpl.create] once a case event has been persisted.
 *
 * Every durable case event goes through that method, so this is the one place a component outside the case
 * runtime can follow a case without subscribing to its live flow, which would keep the runtime from being
 * evicted. Transient events (text chunks, thinking) are never persisted, hence never published.
 *
 * Listeners run synchronously on the thread of the case that stored the event: they must hand the work off
 * and return at once, and never throw.
 */
data class CaseEventCreatedEvent(
    val event: CaseEvent,
)
