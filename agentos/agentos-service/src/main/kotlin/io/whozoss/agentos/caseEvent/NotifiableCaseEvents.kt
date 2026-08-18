package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEventType

/**
 * Event types a notification client (companion SSE stream or push dispatcher) cares
 * about — everything else (message tokens, tool I/O, etc.) is dropped.
 *
 * Shared by [GlobalCaseEventSseController] and [PushNotificationDispatcher] so the two
 * channels never drift apart on what counts as "notifiable."
 */
val NOTIFIABLE_EVENT_TYPES: Set<CaseEventType> =
    setOf(
        CaseEventType.PENDING_CONFIRMATION,
        CaseEventType.CONFIRMATION_RESOLVED,
        CaseEventType.QUESTION,
        CaseEventType.ANSWER,
        CaseEventType.AGENT_FINISHED,
        CaseEventType.ERROR,
    )

fun CaseEvent.isNotifiable(): Boolean = type in NOTIFIABLE_EVENT_TYPES
