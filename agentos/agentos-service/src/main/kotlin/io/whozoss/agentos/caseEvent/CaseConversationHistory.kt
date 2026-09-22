package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.caseFlow.CaseCommandJournal
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import org.springframework.stereotype.Service
import java.util.UUID

/** Read model for people; queued instructions must not enter an agent's active turn. */
@Service
class CaseConversationHistory(
    private val events: CaseEventService,
    private val journal: CaseCommandJournal? = null,
) {
    fun findByCase(caseId: UUID): List<CaseEvent> {
        // Read receipts first: a command materialized during these reads is then found in the
        // event store and wins over its projection. Otherwise its receipt still covers the gap.
        val received = journal?.receivedMessages(caseId, emptySet()).orEmpty()
        val materialized = events.findByParent(caseId)
        if (received.isEmpty()) return materialized
        val receivedById = received.associateBy { it.id }
        val presented = materialized.map { event ->
            // Keep the received time in the conversation after execution. The stored event keeps
            // its execution time so rehydrated agents still see the correct turn ordering.
            val receipt = receivedById[event.id]
            if (event is MessageEvent && receipt != null) event.copy(timestamp = receipt.timestamp) else event
        }
        val ids = materialized.mapTo(mutableSetOf()) { it.id }
        val pending = received.filter { it.id !in ids }
        return (presented + pending).sortedBy { it.timestamp }
    }
}
