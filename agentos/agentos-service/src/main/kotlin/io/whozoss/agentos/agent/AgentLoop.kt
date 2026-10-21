package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.UUID

/**
 * A zero-LLM agent that runs a config-driven step workflow.
 *
 * Unlike [AgentSimple] or [AgentAdvanced], [AgentLoop] never calls an AI provider. It is a thin
 * adapter between the case and [LoopWorkflowRunnerService]:
 *
 * 1. Resolve the [LoopConfig] from [loopConfig] stored on the [AgentConfig].
 * 2. Delegate the workflow to [LoopWorkflowRunnerService].
 * 3. Report the outcome in the case: a summary [MessageEvent] on completion, a [WarnEvent]
 *    when the run could not proceed. Always ends with [AgentFinishedEvent].
 *
 * The triggering message content is not read — configuration lives entirely in [loopConfig].
 *
 * ## Expected loopConfig format
 *
 * ```json
 * {
 *   "version": 1,
 *   "steps": [
 *     { "id": "search", "tool": "SearchTalents", "input": { "params": { "resolveTargets": ["OWNER"] } } },
 *     { "id": "act", "tool": "FreeCaseStarterForUser", "input": { "from": "search" } }
 *   ]
 * }
 * ```
 *
 * @param metadata      Agent identity, inherited from [Entity].
 * @param name          Display name of this agent instance.
 * @param objectMapper  Used to parse [loopConfig].
 * @param runner        Executes the workflow.
 * @param resolvedTools Tools available to this agent.
 * @param triggerUser   The user who triggered the run.
 * @param loopConfig    The [LoopConfig] persisted on [AgentConfig]. Must be non-null
 *                      and parseable for the run to proceed.
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
    /** AgentLoop does not call any LLM — llmProvider and llmModel are null. */
    override val llmProvider: String? = null
    override val llmModel: String? = null

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
                    val config = resolveConfig()
                    logger.info {
                        "[AgentLoop] '$name' starting — ${config.steps.size} step(s) (caseId=$caseId)"
                    }
                    runner.run(
                        config = config,
                        context =
                            LoopRunContext(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentName = name,
                                triggerUser = triggerUser,
                                tools = resolvedTools,
                                caseEvents = events,
                                emitEvent = { emit(it) },
                            ),
                        shouldContinue = shouldContinue,
                    )
                } catch (e: InvalidLoopPayloadException) {
                    logger.error(e) { "[AgentLoop] '$name' invalid loopConfig (caseId=$caseId): ${e.message}" }
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
                    val summary = outcome.lastStepOutput ?: "All steps completed."
                    logger.info { "[AgentLoop] '$name' completed (caseId=$caseId)" }
                    emit(summaryMessage(namespaceId, caseId, summary))
                }

                is LoopRunOutcome.Interrupted -> {
                    logger.info { "[AgentLoop] '$name' interrupted (caseId=$caseId)" }
                    // No extra event on interrupt.
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
     * Resolves the [LoopConfig] from [loopConfig].
     *
     * @throws InvalidLoopPayloadException when [loopConfig] is null, version != 1, steps is empty,
     *   or the JSON cannot be parsed.
     */
    private fun resolveConfig(): LoopConfig {
        if (loopConfig == null) {
            throw InvalidLoopPayloadException("No loopConfig is configured on this agent.")
        }
        return try {
            val config = objectMapper.treeToValue(loopConfig, LoopConfig::class.java)
            if (config.version != 1) {
                throw InvalidLoopPayloadException("loopConfig version must be 1, got ${config.version}.")
            }
            if (config.steps.isEmpty()) {
                throw InvalidLoopPayloadException("loopConfig must have at least one step.")
            }
            logger.info { "[AgentLoop] '$name': loopConfig resolved (${config.steps.size} step(s))" }
            config
        } catch (e: InvalidLoopPayloadException) {
            throw e
        } catch (e: Exception) {
            throw InvalidLoopPayloadException(
                "loopConfig could not be parsed as a valid LoopConfig: ${e.message}",
                e,
            )
        }
    }

    private class InvalidLoopPayloadException(
        message: String,
        cause: Exception? = null,
    ) : IllegalArgumentException(message, cause)

    companion object : KLogging()
}
