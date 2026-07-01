package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import mu.KLogging
import java.util.UUID

/**
 * Internal tool that delegates a task to a sub-agent by creating a child [Case].
 *
 * The tool suspends until the sub-case reaches [CaseStatus.IDLE] or a terminal status,
 * then returns the last agent [MessageEvent] as the result.
 *
 * Sub-cases must run autonomously. If the sub-case blocks on a [QuestionEvent] (the
 * sub-agent needs interactive input), the tool returns a success result containing the
 * pending question text so the parent agent can decide how to handle it (relay to the
 * user or resolve it autonomously). The sub-case is left in IDLE, resumable via
 * [Args.subCaseId].
 *
 * If the sub-case does not complete within [timeoutMs], the delegation fails and the
 * sub-case is killed.
 *
 * This tool is never registered globally. It is instantiated per-agent in
 * [io.whozoss.agentos.agent.AgentServiceImpl] only when
 * [io.whozoss.agentos.agentConfig.AgentConfig.subAgents] is non-empty, with its
 * [allowedAgents] list fixed to the configured allowlist.
 *
 * @param subCaseLauncher  Used to create, resume, and observe sub-cases.
 * @param parentCaseId     Case id of the delegating agent's case, stored on the sub-case.
 * @param namespaceId      Namespace both cases belong to.
 * @param allowedAgents    Allowlist of agent names this tool may delegate to.
 * @param loadCaseEvents   Lambda that loads persisted events for a case id.
 *                         Provided as a lambda to keep [DelegationTool] decoupled from
 *                         [io.whozoss.agentos.caseEvent.CaseEventService] (avoids a
 *                         circular dependency through the Spring context).
 * @param timeoutMs        Max time to wait for the sub-case to reach IDLE (default 5 min).
 */
class DelegationTool(
    private val subCaseLauncher: SubCaseLauncher,
    private val parentCaseId: UUID,
    private val namespaceId: UUID,
    private val allowedAgents: List<String>,
    private val loadCaseEvents: suspend (UUID) -> List<CaseEvent>,
    private val timeoutMs: Long = 5 * 60 * 1_000L,
    private val eventLoadTimeoutMs: Long = EVENT_LOAD_TIMEOUT_MS,
) : StandardTool<DelegationTool.Args> {

    data class Args(
        val agentName: String,
        val task: String,
        /**
         * Optional id of an existing sub-case to resume. When provided, the tool injects
         * [task] as a new user message into that sub-case instead of creating a new one.
         * The sub-case must be in [CaseStatus.IDLE] — resuming a running or terminal case
         * is rejected.
         */
        val subCaseId: UUID? = null,
    )

    override val name = "DELEGATE__delegate"
    override val description: String = when {
        allowedAgents.isEmpty() ->
            "No sub-agents are currently available for delegation. Do not attempt to delegate — handle the request yourself or inform the user that no sub-agent can address it."
        else ->
            "Delegate a task to a specialized sub-agent and wait for the result. " +
                "The sub-agent runs autonomously — if it needs to ask a question, the question is returned so you can relay it or resolve it yourself. " +
                "To resume a previous sub-case (e.g. after relaying a question), provide its subCaseId. " +
                "Available agents: ${allowedAgents.joinToString(", ")}."
    }
    override val version = "1.0.0"
    override val paramType: Class<Args> = Args::class.java

    override val inputSchema: String = objectMapper
        .createObjectNode().apply {
            put("type", "object")
            putObject("properties").apply {
                putObject("agentName").apply {
                    put("type", "string")
                    put("description", "Name of the sub-agent to delegate to.")
                    putArray("enum").also { arr -> allowedAgents.forEach(arr::add) }
                }
                putObject("task").apply {
                    put("type", "string")
                    put("description", "Full, self-contained task description for the sub-agent.")
                }
                putObject("subCaseId").apply {
                    put("type", "string")
                    put("format", "uuid")
                    put(
                        "description",
                        "Optional UUID of an existing IDLE sub-case to resume. " +
                            "When provided, the task is injected as a new message into that sub-case " +
                            "instead of creating a new one.",
                    )
                }
            }
            putArray("required").add("agentName").add("task")
        }
        .let { objectMapper.writeValueAsString(it) }

    override suspend fun execute(
        input: Args?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult(
                output = "Delegation requires agentName and task arguments.",
                success = false,
            )
        }
        if (input.agentName !in allowedAgents) {
            return ToolExecutionResult(
                output =
                    "Agent '${input.agentName}' is not in the delegation allowlist. " +
                        "Allowed agents: ${allowedAgents.joinToString(", ")}.",
                success = false,
            )
        }
        val userId =
            context.userId
                ?: return ToolExecutionResult(
                    output = "Delegation requires a user context (userId is null).",
                    success = false,
                )

        val agentName = input.agentName
        val task = input.task

        val subRuntime =
            when (val resumeId = input.subCaseId) {
                null -> {
                    logger.info { "[DelegationTool] Delegating to '$agentName' from parent case $parentCaseId" }
                    subCaseLauncher.startSubCase(
                        parentCaseId = parentCaseId,
                        namespaceId = namespaceId,
                        agentName = agentName,
                        task = task,
                        userId = userId,
                    )
                }

                else -> {
                    logger.info { "[DelegationTool] Resuming sub-case $resumeId for agent '$agentName'" }
                    subCaseLauncher.resumeSubCase(
                        subCaseId = resumeId,
                        agentName = agentName,
                        task = task,
                        userId = userId,
                        allowedAgents = allowedAgents,
                    )
                }
            }
        val subCaseId = subRuntime.id
        logger.info { "[DelegationTool] Sub-case $subCaseId active, waiting for completion (timeout=${timeoutMs}ms)" }

        // Phase 1: wait for the sub-case to reach IDLE or a terminal status.
        // TimeoutCancellationException here means the run timeout fired — isolated so
        // the event-load timeout below cannot accidentally trigger this handler.
        val finalStatus =
            try {
                withTimeout(timeoutMs) {
                    subRuntime.statusFlow
                        .filter { it == CaseStatus.IDLE || it.isTerminal() }
                        .first()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logger.warn { "[DelegationTool] Sub-case $subCaseId timed out after ${timeoutMs}ms — killing" }
                runCatching { subCaseLauncher.killSubCase(subCaseId) }
                return ToolExecutionResult(
                    output =
                        "Delegation to '$agentName' timed out after ${timeoutMs / 1000}s. " +
                            "The sub-agent did not complete in time.",
                    success = false,
                    metadata = mapOf("subCaseId" to subCaseId.toString()),
                )
            }

        // Phase 2: sub-case has stopped — evaluate the outcome.
        if (finalStatus.isTerminal()) {
            logger.warn { "[DelegationTool] Sub-case $subCaseId ended with terminal status $finalStatus" }
            return ToolExecutionResult(
                output = "Sub-case ended with status $finalStatus without producing a result.",
                success = false,
                metadata = mapOf("subCaseId" to subCaseId.toString()),
            )
        }

        // Phase 3: load events to extract the result. Isolated timeout so a slow event
        // store does not trigger the run-timeout handler above (which would wrongly kill
        // a successfully completed sub-case and report the wrong duration).
        val events =
            try {
                withTimeout(eventLoadTimeoutMs) { loadCaseEvents(subCaseId) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logger.warn { "[DelegationTool] Sub-case $subCaseId: event load timed out after ${eventLoadTimeoutMs}ms" }
                return ToolExecutionResult(
                    output = "Sub-case completed but its event history could not be loaded in time.",
                    success = false,
                    metadata = mapOf("subCaseId" to subCaseId.toString()),
                )
            }

        // A QuestionEvent as the last significant event means the sub-agent is waiting
        // for interactive input it cannot receive autonomously. Return it as a success so
        // the parent agent can decide how to handle it (relay to the user or resolve it
        // autonomously). The sub-case stays IDLE and can be resumed via subCaseId.
        val lastQuestion = events.filterIsInstance<QuestionEvent>().lastOrNull()
        val lastAgentMessage = events
            .filterIsInstance<MessageEvent>()
            .lastOrNull { it.actor.role == ActorRole.AGENT }
        // A question is pending when no agent message was emitted after it.
        val lastQuestionIsPending = lastQuestion != null &&
            (lastAgentMessage == null || events.indexOf(lastQuestion) > events.indexOf(lastAgentMessage))

        return if (lastQuestionIsPending) {
            logger.info { "[DelegationTool] Sub-case $subCaseId reached IDLE with a pending question" }
            ToolExecutionResult(
                output = objectMapper.writeValueAsString(
                    mapOf(
                        "subCaseId" to subCaseId.toString(),
                        "pendingQuestion" to lastQuestion!!.question,
                        "options" to lastQuestion.options,
                    ),
                ),
                success = true,
                metadata = mapOf("subCaseId" to subCaseId.toString()),
            )
        } else {
            val result = extractLastAgentMessage(events)
            logger.info { "[DelegationTool] Sub-case $subCaseId finished, result length=${result.length}" }
            ToolExecutionResult(
                output = objectMapper.writeValueAsString(mapOf("subCaseId" to subCaseId.toString(), "result" to result)),
                success = true,
                metadata = mapOf("subCaseId" to subCaseId.toString()),
            )
        }
    }

    /**
     * Extracts the text of the last [MessageEvent] emitted by an agent from a pre-loaded
     * event list. Falls back to a generic message if no agent message is found.
     */
    private fun extractLastAgentMessage(events: List<CaseEvent>): String =
        events
            .filterIsInstance<MessageEvent>()
            .lastOrNull { it.actor.role == ActorRole.AGENT }
            ?.content
            ?.filterIsInstance<MessageContent.Text>()
            ?.joinToString("\n") { it.content }
            ?.takeIf { it.isNotBlank() }
            ?: "Sub-agent completed the task but produced no text output."

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()

        /** Max time allowed to load sub-case events after the sub-case has completed. */
        private const val EVENT_LOAD_TIMEOUT_MS = 10_000L
    }
}
