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
import io.whozoss.agentos.workflow.LoopDataSessionContext
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopSteps
import io.whozoss.agentos.workflow.LoopStepsTranslator
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
 * 1. Resolve the [AgentLoopPayload] from [loopConfig] (see "Payload resolution" below).
 * 2. Delegate the workflow to [LoopWorkflowRunnerService].
 * 3. Report the outcome in the case: a summary [MessageEvent] on completion, a [WarnEvent]
 *    when the run could not proceed. Always ends with [AgentFinishedEvent].
 *
 * The text of the triggering message is not read; only its session context is.
 *
 * ## Process and data
 *
 * The agent's [loopConfig] describes the *process* of the loop. The *data* of a run (what to search, which agent
 * and prompt to start, ...) is the `loopData` entry of the session context of the user message that triggers the
 * run, written by whoever starts it (see [LoopDataSessionContext]); the steps read it through placeholders.
 *
 * ## Payload resolution
 *
 * - No [loopConfig]: the run cannot proceed and a [WarnEvent] is emitted.
 * - A [loopConfig] with a `steps` field: the `loopData` of the most recent user [MessageEvent] is required
 *   (a [WarnEvent] when missing); the `${loopData...}` placeholders of the steps are resolved against it and the
 *   result is validated ([LoopStepsTranslator]).
 * - Any other [loopConfig] is in the legacy `search`/`act` format, used as is; it takes no `loopData` and is what
 *   a run launched from the UI uses.
 *
 * Whatever is invalid is reported as a [WarnEvent] before anything runs.
 *
 * ## loopConfig formats
 *
 * Steps format, with the `loopData` it reads:
 * ```json
 * {
 *   "steps": [
 *     { "id": "search", "type": "SEARCH", "tool": "${loopData.searchToolName}", "args": "${loopData.searchToolArgs}" },
 *     { "id": "start-cases", "type": "START_CASES", "tool": "FreeCaseStarterForUser",
 *       "args": { "agentName": "${loopData.agentName}", "prompt": "${loopData.prompt}" },
 *       "scheduledPromptId": "${loopData.scheduledPromptId}" }
 *   ]
 * }
 * ```
 *
 * Legacy format:
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
 * @param loopConfig    The loop described by the [AgentConfig], in either format.
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
                    logger.error(e) { "[AgentLoop] '$name' invalid loop payload (caseId=$caseId): ${e.message}" }
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

    /**
     * Resolves the [AgentLoopPayload] of the run from [loopConfig], in its `steps` or legacy format.
     *
     * @throws InvalidLoopPayloadException when there is no [loopConfig], or the one found (or the `loopData` it
     *   needs) is missing or invalid.
     */
    private fun resolvePayload(events: List<CaseEvent>): AgentLoopPayload {
        val config =
            loopConfig ?: throw InvalidLoopPayloadException("No loopConfig is configured on this agent.")

        val (payload, format) =
            when {
                config.has(STEPS_FIELD) -> stepsPayload(config, events) to "steps"
                else -> legacyPayload(config) to "legacy"
            }

        if (payload.search.tool.isBlank()) {
            throw InvalidLoopPayloadException("The loopConfig ($format format) has a blank search tool.")
        }
        logger.info { "[AgentLoop] '$name': loopConfig resolved ($format format, tool=${payload.search.tool})" }
        return payload
    }

    /** The `steps` format: parsed, resolved against the `loopData` of the last user message, then validated. */
    private fun stepsPayload(
        config: JsonNode,
        events: List<CaseEvent>,
    ): AgentLoopPayload {
        val steps =
            try {
                objectMapper.treeToValue(config, LoopSteps::class.java)
            } catch (e: Exception) {
                throw InvalidLoopPayloadException("loopConfig could not be parsed as valid steps: ${e.message}", e)
            }
        val lastUserMessage =
            events.filterIsInstance<MessageEvent>().lastOrNull { it.actor.role == ActorRole.USER }
        val loopData =
            readLoopData(lastUserMessage) ?: throw InvalidLoopPayloadException(
                "This agent loop requires a '${SessionContextKeys.LOOP_DATA}' entry in the session context of the " +
                    "triggering message.",
            )
        return try {
            LoopStepsTranslator.translate(steps, loopData)
        } catch (e: IllegalArgumentException) {
            throw InvalidLoopPayloadException("Invalid loopConfig: ${e.message}", e)
        }
    }

    private fun readLoopData(message: MessageEvent?): JsonNode? =
        try {
            LoopDataSessionContext.read(message?.sessionContext, objectMapper)
        } catch (e: IllegalArgumentException) {
            throw InvalidLoopPayloadException(e.message ?: "Invalid loopData", e)
        }

    private fun legacyPayload(config: JsonNode): AgentLoopPayload =
        try {
            objectMapper.treeToValue(config, AgentLoopPayload::class.java)
        } catch (e: Exception) {
            throw InvalidLoopPayloadException(
                "loopConfig could not be parsed as a valid AgentLoopPayload: ${e.message}",
                e,
            )
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

        /** Field whose presence in the `loopConfig` selects the `steps` format over the legacy one. */
        private const val STEPS_FIELD = "steps"
    }
}
