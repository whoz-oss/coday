package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.CaseWorker
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
import io.whozoss.agentos.workflow.LoopConfigResolver
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
 * 1. Resolve the [LoopConfig] via the injected [LoopConfigResolver], which is contributed by
 *    whatever initiated the run (e.g. [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor]).
 * 2. Delegate the workflow to [LoopWorkflowRunnerService].
 * 3. Report the outcome in the case: a summary [MessageEvent] on completion, a [WarnEvent]
 *    when the run could not proceed. Always ends with [AgentFinishedEvent].
 *
 * The agent carries no loop configuration of its own — configuration is supplied by a
 * [LoopConfigResolver] contributed by whatever initiated the run; the agent does not read
 * the triggering message for config.
 *
 * @param metadata        Agent identity, inherited from [Entity].
 * @param name            Display name of this agent instance.
 * @param configResolver  Supplies the [LoopConfig] for the running case.
 * @param runner          Executes the workflow.
 * @param resolvedTools   Tools available to this agent.
 * @param triggerUser     The user who triggered the run.
 */
class AgentLoop(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val configResolver: LoopConfigResolver,
    private val runner: LoopWorkflowRunnerService,
    private val resolvedTools: Collection<StandardTool<*>> = emptyList(),
    private val triggerUser: User? = null,
) : CaseWorker {
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
                    val config = resolveConfig(caseId)
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
                    logger.error(e) { "[AgentLoop] '$name' invalid loop config (caseId=$caseId): ${e.message}" }
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
     * Resolves the [LoopConfig] for [caseId] via the injected [LoopConfigResolver].
     *
     * @throws InvalidLoopPayloadException when the resolver returns null (unrecognised case),
     *   throws an exception, or the config fails validation.
     */
    private fun resolveConfig(caseId: UUID): LoopConfig {
        val config = try {
            configResolver.resolve(caseId)
        } catch (e: Exception) {
            throw InvalidLoopPayloadException("Could not resolve the loop configuration: ${e.message}", e)
        } ?: throw InvalidLoopPayloadException(
            "No loop configuration is available for this case. A loop case must be started by an " +
                "initiator that registers its configuration (e.g. a scheduled prompt).",
        )
        if (config.version != 1) throw InvalidLoopPayloadException("Loop config version must be 1, got ${config.version}.")
        if (config.steps.isEmpty()) throw InvalidLoopPayloadException("Loop config must declare at least one step.")
        return config
    }

    private class InvalidLoopPayloadException(
        message: String,
        cause: Exception? = null,
    ) : IllegalArgumentException(message, cause)

    companion object : KLogging()
}
