package io.whozoss.agentos.a2a

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import org.springframework.stereotype.Service

@Service
class A2aMappingService {

    fun toA2aTaskState(status: CaseStatus): String = when (status) {
        CaseStatus.PENDING -> "TASK_STATE_SUBMITTED"
        CaseStatus.RUNNING -> "TASK_STATE_WORKING"
        CaseStatus.IDLE -> "TASK_STATE_COMPLETED"
        CaseStatus.ERROR -> "TASK_STATE_FAILED"
        CaseStatus.KILLED -> "TASK_STATE_CANCELED"
    }

    fun toA2aTask(case: Case, events: List<CaseEvent>, historyLength: Int? = null): A2aTask {
        val taskId = case.metadata.id.toString()
        val messageEvents = events.filterIsInstance<MessageEvent>()
        return A2aTask(
            id = taskId,
            contextId = taskId,
            status = A2aTaskStatus(
                state = toA2aTaskState(case.status),
                timestamp = case.metadata.modified.toString(),
            ),
            artifacts = extractArtifacts(messageEvents),
            history = buildHistory(messageEvents, historyLength).takeIf { it.isNotEmpty() },
        )
    }

    fun extractArtifacts(events: List<CaseEvent>): List<A2aArtifact>? {
        val agentMessages = events
            .filterIsInstance<MessageEvent>()
            .filter { it.actor.role == ActorRole.AGENT }
        if (agentMessages.isEmpty()) return null
        return agentMessages.mapIndexed { index, event ->
            val textParts = event.content
                .filterIsInstance<MessageContent.Text>()
                .map { A2aPart(text = it.content) }
            A2aArtifact(
                artifactId = event.metadata.id.toString(),
                name = "Agent response ${index + 1}",
                parts = textParts,
            )
        }
    }

    fun buildHistory(events: List<CaseEvent>, historyLength: Int? = null): List<A2aMessage> {
        val messageEvents = events.filterIsInstance<MessageEvent>()
        val limited = if (historyLength != null) messageEvents.takeLast(historyLength) else messageEvents
        return limited.map { event ->
            val role = if (event.actor.role == ActorRole.USER) "ROLE_USER" else "ROLE_AGENT"
            val parts = event.content
                .filterIsInstance<MessageContent.Text>()
                .map { A2aPart(text = it.content) }
            A2aMessage(
                messageId = event.metadata.id.toString(),
                role = role,
                parts = parts,
                taskId = event.caseId.toString(),
            )
        }
    }

    fun toA2aSkill(agentConfig: AgentConfig): A2aAgentSkill {
        val description = agentConfig.instructions
            ?.take(200)
            ?: agentConfig.description
            ?: agentConfig.name
        return A2aAgentSkill(
            id = agentConfig.metadata.id.toString(),
            name = agentConfig.name,
            description = description,
            tags = listOf("agent"),
        )
    }
}
