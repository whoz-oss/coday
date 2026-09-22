package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.time.Instant
import java.util.UUID

/**
 * Repository for CaseEvent persistence.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 *
 * Parent type is UUID representing the caseId.
 */
interface CaseEventRepository : EntityRepository<CaseEvent, UUID> {
    fun participatingAgents(caseIds: Collection<UUID>): List<ParticipatingAgent> = caseIds
        .flatMap { findByParent(it) }.sortedBy { it.timestamp }.mapNotNull {
            when (it) {
                is io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent -> ParticipatingAgent(it.agentId, it.agentName)
                is io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent -> ParticipatingAgent(it.agentId, it.agentName)
                else -> null
            }
        }.associateBy { it.id }.values.sortedBy { it.name }

    /**
     * Return the timestamp of the most recent [io.whozoss.agentos.sdk.caseEvent.MessageEvent]
     * for each of the given [caseIds], as a map of caseId → timestamp.
     *
     * Cases with no messages are absent from the result. The caller should fall back to the
     * case's own creation timestamp for such cases.
     */
    fun findLastMessageTimestamps(caseIds: Collection<UUID>): Map<UUID, Instant>
}

data class ParticipatingAgent(val id: UUID, val name: String)
