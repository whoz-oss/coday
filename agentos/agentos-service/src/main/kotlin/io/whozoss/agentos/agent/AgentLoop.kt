package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.UUID

/**
 * A zero-LLM agent that runs a hardcoded SEARCH → ACT workflow: one child case per entity
 * returned by a Search tool.
 *
 * Unlike [AgentSimple] or [AgentAdvanced], [AgentLoop] never calls an AI provider. It is a thin
 * adapter between the case and [LoopWorkflowRunnerService]:
 *
 * 1. Resolve the [AgentLoopPayload] to run (see below).
 * 2. Delegate the workflow to [LoopWorkflowRunnerService].
 * 3. Report the outcome in the case: a summary [MessageEvent] on completion, a [WarnEvent]
 *    when the run could not proceed. Always ends with [AgentFinishedEvent].
 *
 * ## Which payload runs
 *
 * The triggering message *content* is never read. The payload is, in this order:
 * 1. the `flowConfiguration` of the session context of the last user message
 *    ([SessionContextKeys.FLOW_CONFIGURATION]), set by whoever starts the run: the scheduler, the UI launch page,
 *    any API client. The loop does not know who started it;
 * 2. else [loopConfig], the default persisted on the agent;
 * 3. else the run is aborted with a [WarnEvent].
 *
 * An invalid `flowConfiguration` aborts the run, it never falls back to [loopConfig] (that would run something the
 * caller did not ask for). A blank `search.tool` or `act.agentName` is invalid. Providing a `flowConfiguration`
 * grants nothing: the run is bounded by the integrations of the loop agent, not by where its payload comes from.
 *
 * ## Payload format
 *
 * ```json
 * {
 *   "search": {
 *     "tool": "SearchTalents",
 *     "params": { "endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] }
 *   },
 *   "act": { "agentName": "talent-analyzer", "prompt": "Analyse this entity: {entityId}" }
 * }
 * ```
 *
 * @param metadata      Agent identity, inherited from [Entity].
 * @param name          Display name of this agent instance.
 * @param objectMapper  Used to parse the payload.
 * @param runner        Executes the workflow.
 * @param resolvedTools Tools available to this agent — the Search tool is looked up here.
 * @param triggerUser   The user who triggered the run.
 * @param loopConfig    The default [AgentLoopPayload] persisted on [AgentConfig].
 */
class AgentLoop(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val objectMapper: ObjectMapper,
    private val runner: LoopWorkflowRunnerService,
    private val resolvedTools: Collection<StandardTool<*>> = emptyList(),
    private val triggerUser: User? = null,
    private val loopConfig: JsonNode? = null,
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

            emit(
                AgentRunningEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = id,
                    agentName = name,
                    // AgentLoop never calls an LLM — omit provider/model.
                    llmProvider = null,
                    llmModel = null,
                ),
            )

            val outcome =
                try {
                    val payload = resolvePayload(events)
                    logger.info {
                        "[AgentLoop] '$name' starting — tool=${payload.search.tool}, actAgent=${payload.act.agentName} (caseId=$caseId)"
                    }
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
                            ),
                        shouldContinue = shouldContinue,
                    )
                } catch (e: InvalidLoopPayloadException) {
                    logger.error(e) { "[AgentLoop] '$name' invalid payload (caseId=$caseId): ${e.message}" }
                    LoopRunOutcome.Aborted(e.message ?: "Invalid AgentLoop payload")
                }

            when (outcome) {
                is LoopRunOutcome.Aborted -> {
                    logger.warn { "[AgentLoop] '$name' aborted: ${outcome.reason} (caseId=$caseId)" }
                    emit(
                        WarnEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            message = "AgentLoop '$name': ${outcome.reason}",
                        ),
                    )
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

    /** Resolves the payload to run, see the class KDoc; throws [InvalidLoopPayloadException] when there is none or it is invalid. */
    private fun resolvePayload(events: List<CaseEvent>): AgentLoopPayload {
        val fromContext =
            events
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.sessionContext
                ?.get(SessionContextKeys.FLOW_CONFIGURATION)
        val (source, node) =
            when {
                fromContext != null -> "flowConfiguration of the triggering message" to objectMapper.valueToTree<JsonNode>(fromContext)
                loopConfig != null -> "loopConfig" to loopConfig
                else ->
                    throw InvalidLoopPayloadException(
                        "No flowConfiguration in the context of the triggering message and no loopConfig on this agent.",
                    )
            }
        return try {
            val payload = objectMapper.treeToValue(node, AgentLoopPayload::class.java)
            if (payload.search.tool.isBlank()) throw InvalidLoopPayloadException("The $source has a blank 'search.tool' field.")
            if (payload.act.agentName.isBlank()) throw InvalidLoopPayloadException("The $source has a blank 'act.agentName' field.")
            logger.info { "[AgentLoop] '$name': payload resolved from $source (tool=${payload.search.tool})" }
            payload
        } catch (e: InvalidLoopPayloadException) {
            throw e
        } catch (e: Exception) {
            throw InvalidLoopPayloadException(
                "The $source could not be parsed as a valid AgentLoopPayload: ${e.message}",
                e,
            )
        }
    }

    private class InvalidLoopPayloadException(
        message: String,
        cause: Exception? = null,
    ) : IllegalArgumentException(message, cause)

    companion object : KLogging() {
        /**
         * Sentinel values satisfying the [Agent.llmProvider] and [Agent.llmModel] contract.
         * AgentLoop never calls an LLM — these values identify the implementation, not a provider.
         */
        const val PROVIDER_NAME = "none"
        const val MODEL_NAME = "agent-loop"
    }
}
