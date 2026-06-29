package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
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
 * The tool blocks (via [runBlocking]) until the sub-case reaches [CaseStatus.IDLE]
 * or a terminal status, then returns the last agent [MessageEvent] as the result.
 *
 * Sub-cases must run autonomously: interactive [io.whozoss.agentos.sdk.caseEvent.QuestionEvent]s
 * are not supported. If the sub-case blocks on a question or does not complete within
 * [timeoutMs], the delegation fails and the sub-case is killed.
 *
 * This tool is never registered globally. It is instantiated per-agent in
 * [io.whozoss.agentos.agent.AgentServiceImpl] only when
 * [io.whozoss.agentos.agentConfig.AgentConfig.subAgents] is non-empty, with its
 * [allowedAgents] list fixed to the configured allowlist.
 *
 * @param subCaseLauncher  Used to create and observe sub-cases.
 * @param parentCaseId     Case id of the delegating agent's case, stored on the sub-case.
 * @param namespaceId      Namespace both cases belong to.
 * @param allowedAgents    Whitelist of agent names this tool may delegate to.
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
    )

    override val name = "DELEGATE__delegate"
    override val description: String = when {
        allowedAgents.isEmpty() ->
            "No sub-agents are currently available for delegation. Do not attempt to delegate — handle the request yourself or inform the user that no sub-agent can address it."
        else ->
            "Delegate a task to a specialized sub-agent and wait for the result. " +
                "The sub-agent runs autonomously — interactive questions are not supported. " +
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
        logger.info { "[DelegationTool] Delegating to '$agentName' from parent case $parentCaseId" }

        val subRuntime =
            subCaseLauncher.startSubCase(
                parentCaseId = parentCaseId,
                namespaceId = namespaceId,
                agentName = agentName,
                task = task,
                userId = userId,
            )
        val subCaseId = subRuntime.id
        logger.info { "[DelegationTool] Sub-case $subCaseId started, waiting for completion (timeout=${timeoutMs}ms)" }

        return try {
            val finalStatus =
                withTimeout(timeoutMs) {
                    subRuntime.statusFlow
                        .filter { it == CaseStatus.IDLE || it.isTerminal() }
                        .first()
                }

            when {
                finalStatus.isTerminal() && finalStatus != CaseStatus.IDLE -> {
                    logger.warn { "[DelegationTool] Sub-case $subCaseId ended with terminal status $finalStatus" }
                    ToolExecutionResult(
                        output = "Sub-case ended with status $finalStatus without producing a result.",
                        success = false,
                        metadata = mapOf("subCaseId" to subCaseId.toString()),
                    )
                }

                else -> {
                    val result = withTimeout(eventLoadTimeoutMs) { extractLastAgentMessage(subCaseId) }
                    logger.info { "[DelegationTool] Sub-case $subCaseId finished, result length=${result.length}" }
                    ToolExecutionResult(
                        output = objectMapper.writeValueAsString(mapOf("subCaseId" to subCaseId.toString(), "result" to result)),
                        success = true,
                        metadata = mapOf("subCaseId" to subCaseId.toString()),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "[DelegationTool] Sub-case $subCaseId timed out after ${timeoutMs}ms — killing" }
            runCatching { subCaseLauncher.killSubCase(subCaseId) }
            ToolExecutionResult(
                output =
                    "Delegation to '$agentName' timed out after ${timeoutMs / 1000}s. " +
                        "The sub-agent did not complete in time.",
                success = false,
                metadata = mapOf("subCaseId" to subCaseId.toString()),
            )
        }
    }

    /**
     * Reads the last [MessageEvent] emitted by an agent in the sub-case event history.
     * Falls back to a generic message if no agent message is found.
     */
    private suspend fun extractLastAgentMessage(subCaseId: UUID): String =
        loadCaseEvents(subCaseId)
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
