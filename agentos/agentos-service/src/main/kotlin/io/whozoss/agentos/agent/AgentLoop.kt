package io.whozoss.agentos.agent

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.AgentMention
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.CaseLauncher
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.UUID

/**
 * A zero-LLM agent that runs a hardcoded SEARCH → ACT workflow: one child case per entity
 * returned by a Search tool.
 *
 * Unlike [AgentSimple] or [AgentAdvanced], [AgentLoop] never calls an AI provider. It is a thin
 * adapter between the case and [LoopWorkflowRunner]:
 *
 * 1. Parse the [AgentLoopPayload] JSON from the first user [MessageEvent], ignoring the
 *    leading `@agentName` mention used to route the message to this agent.
 * 2. Delegate the workflow to [LoopWorkflowRunner].
 * 3. Report the outcome in the case: a summary [MessageEvent] on completion, a [WarnEvent]
 *    when the run could not proceed. Always ends with [AgentFinishedEvent].
 *
 * ## Expected message format
 *
 * ```
 * @loop-agent {
 *   "tool": "SearchTalents",
 *   "searchInput": { "endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] },
 *   "act": { "agentName": "talent-analyzer", "promptTemplate": "Analyse this entity: {entityId}" }
 * }
 * ```
 *
 * @param metadata      Agent identity, inherited from [Entity].
 * @param name          Display name of this agent instance.
 * @param objectMapper  Used to parse the JSON payload.
 * @param runner        Executes the workflow.
 * @param resolvedTools Tools available to this agent — the Search tool is looked up here.
 * @param triggerUser   The user who triggered the run.
 * @param caseLauncher  Starts the child cases; null outside a live case.
 */
class AgentLoop(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val objectMapper: ObjectMapper,
    private val runner: LoopWorkflowRunner,
    private val resolvedTools: Collection<StandardTool<*>> = emptyList(),
    private val triggerUser: User? = null,
    private val caseLauncher: CaseLauncher? = null,
) : Agent {
    /**
     * AgentLoop does not call any LLM — these fields satisfy the [Agent] contract
     * but carry no runtime meaning for this implementation.
     */
    override val llmProvider: String = PROVIDER_NAME
    override val llmModel: String = MODEL_NAME

    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId =
                events.firstOrNull()?.namespaceId
                    ?: throw IllegalArgumentException("[AgentLoop] No events provided — cannot resolve namespaceId")
            val caseId =
                events.firstOrNull()?.caseId
                    ?: throw IllegalArgumentException("[AgentLoop] No events provided — cannot resolve caseId")

            // Honour kill/interrupt signal before doing any work.
            if (!shouldContinue()) {
                logger.info { "[AgentLoop] '$name' interrupted before start (caseId=$caseId)" }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

            val outcome =
                try {
                    val payload = parsePayload(events)
                    logger.info { "[AgentLoop] '$name' starting — tool=${payload.tool}, actAgent=${payload.act.agentName} (caseId=$caseId)" }
                    runner.run(
                        payload = payload,
                        context =
                            LoopRunContext(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentName = name,
                                triggerUser = triggerUser,
                                tools = resolvedTools,
                                caseEvents = events,
                                caseLauncher = caseLauncher,
                            ),
                        shouldContinue = shouldContinue,
                    )
                } catch (e: InvalidLoopPayloadException) {
                    LoopRunOutcome.Aborted(e.message ?: "Invalid AgentLoop payload")
                }

            when (outcome) {
                is LoopRunOutcome.Aborted -> {
                    logger.warn { "[AgentLoop] '$name' aborted: ${outcome.reason} (caseId=$caseId)" }
                    emit(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "AgentLoop '$name': ${outcome.reason}"))
                }

                is LoopRunOutcome.Completed -> {
                    val summary = outcome.summary()
                    logger.info { "[AgentLoop] '$name' completed: $summary (caseId=$caseId)" }
                    emit(summaryMessage(namespaceId, caseId, summary))
                }
            }

            emit(finishedEvent(namespaceId, caseId))
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun summaryMessage(
        namespaceId: UUID,
        caseId: UUID,
        summary: String,
    ) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id = id.toString(), displayName = name, role = ActorRole.AGENT),
        content = listOf(MessageContent.Text(summary)),
    )

    private fun finishedEvent(
        namespaceId: UUID,
        caseId: UUID,
    ) = AgentFinishedEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = id,
        agentName = name,
        // No LLM was used — provider/model are omitted.
        llmProvider = null,
        llmModel = null,
    )

    /**
     * Extracts and parses the JSON payload from the first user [MessageEvent], after removing
     * the leading `@agentName` mention.
     *
     * @throws InvalidLoopPayloadException with a user-facing reason when no payload can be parsed.
     */
    private fun parsePayload(events: List<CaseEvent>): AgentLoopPayload {
        val firstUserMessage =
            events
                .filterIsInstance<MessageEvent>()
                .firstOrNull { it.actor.role == ActorRole.USER }
                ?: throw InvalidLoopPayloadException("No user message found in the case.")

        val json =
            AgentMention
                .strip(
                    firstUserMessage.content
                        .filterIsInstance<MessageContent.Text>()
                        .joinToString("\n") { it.content },
                ).takeIf { it.isNotBlank() }
                ?: throw InvalidLoopPayloadException("The first message carries no JSON payload.")

        val payload =
            try {
                objectMapper.readValue(json, AgentLoopPayload::class.java)
            } catch (e: Exception) {
                logger.warn(e) { "[AgentLoop] Failed to parse payload JSON: $json" }
                val reason = (e as? JsonProcessingException)?.originalMessage ?: e.message
                throw InvalidLoopPayloadException("The payload is not a valid AgentLoop JSON: $reason")
            }
        if (payload.tool.isBlank()) throw InvalidLoopPayloadException("The payload is missing the 'tool' field.")
        return payload
    }

    private class InvalidLoopPayloadException(
        message: String,
    ) : IllegalArgumentException(message)

    companion object : KLogging() {
        /**
         * Sentinel values satisfying the [Agent.llmProvider] and [Agent.llmModel] contract.
         * AgentLoop never calls an LLM — these values identify the implementation, not a provider.
         */
        const val PROVIDER_NAME = "none"
        const val MODEL_NAME = "agent-loop"
    }
}
