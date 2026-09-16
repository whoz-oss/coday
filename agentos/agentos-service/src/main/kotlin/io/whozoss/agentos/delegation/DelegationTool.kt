package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseOutcome
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import mu.KLogging
import java.util.UUID

/**
 * Internal tool that delegates one or more tasks to sub-agents by creating child [Case]s.
 *
 * All delegations are launched in parallel and the tool suspends until every sub-case
 * reaches [CaseStatus.IDLE] or a terminal status (or the global [timeoutMs] fires).
 * Results are aggregated into a JSON array — one entry per delegation — and returned
 * as a single [ToolExecutionResult]. The overall [ToolExecutionResult.success] is `true`
 * when at least one delegation succeeded.
 *
 * Each delegation entry in the response carries:
 * - `agentName` — the sub-agent that handled the task
 * - `subCaseId` — UUID of the created (or resumed) sub-case, usable for follow-up calls
 * - `success` — whether this individual delegation produced a usable result
 * - `result` — the last agent message text (when success and no pending question)
 * - `pendingQuestion` — the question text when the sub-agent is waiting for input
 * - `options` — optional list of choices for the pending question
 * - `error` — error description when success is false
 *
 * **Timeout** applies to the entire batch: all sub-cases must complete within [timeoutMs].
 * Sub-cases still running when the timeout fires are killed individually.
 *
 * **Resume**: a delegation with a non-null [Delegation.subCaseId] resumes an existing
 * IDLE sub-case instead of creating a new one.
 *
 * This tool is never registered globally. It is instantiated per-agent in
 * [io.whozoss.agentos.agent.AgentServiceImpl] only when
 * [io.whozoss.agentos.agentConfig.AgentConfig.subAgents] is non-empty.
 *
 * @param subCaseManager  Used to create, resume, and observe sub-cases.
 * @param parentCaseId     Case id of the delegating agent's case.
 * @param namespaceId      Namespace both cases belong to.
 * @param allowedAgents    Allowlist of agent names this tool may delegate to.
 * @param loadCaseEvents   Lambda that loads persisted events for a case id.
 * @param timeoutMs        Max wall-clock time for the entire batch (default 5 min).
 */
class DelegationTool(
    private val subCaseManager: SubCaseManager,
    private val parentCaseId: UUID,
    private val namespaceId: UUID,
    private val allowedAgents: List<String>,
    private val loadCaseEvents: suspend (UUID) -> List<CaseEvent>,
    private val timeoutMs: Long = 5 * 60 * 1_000L,
    private val eventLoadTimeoutMs: Long = EVENT_LOAD_TIMEOUT_MS,
) : StandardTool<DelegationTool.Args> {
    /**
     * A single delegation within the batch.
     *
     * @param agentName  Name of the sub-agent to delegate to (must be in [allowedAgents]).
     * @param task       Full, self-contained task description for the sub-agent.
     * @param subCaseId  Optional UUID of an existing IDLE sub-case to resume instead of
     *                   creating a new one.
     */
    data class Delegation(
        val agentName: String,
        val task: String,
        val subCaseId: UUID? = null,
    )

    data class Args(
        val delegations: List<Delegation>,
    )

    override val name = "DELEGATE__delegate"
    override val description: String =
        when {
            allowedAgents.isEmpty() -> {
                """
                No sub-agents are currently available for delegation. 
                Do not attempt to delegate — handle the request yourself or inform the user that no sub-agent can address it.
                """.trimIndent()
            }

            else -> {
                """
                Delegate one or more tasks to specialized sub-agents and wait for all results. 
                All delegations run in parallel — pass multiple entries to fan out work concurrently. 
                Sub-agents run autonomously; if one needs to ask a question, the question is returned so you can relay it or resolve it, then resume via subCaseId. 
                Available agents: ${allowedAgents.joinToString(", ")}.
                """.trimIndent()
            }
        }
    override val version = "1.0.0"
    override val paramType: Class<Args> = Args::class.java

    override val inputSchema: String =
        objectMapper
            .createObjectNode()
            .apply {
                put("type", "object")
                putObject("properties").apply {
                    putObject("delegations").apply {
                        put("type", "array")
                        put("description", "List of tasks to delegate. All run in parallel.")
                        putObject("items").apply {
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
                                            "When provided, the task is injected as a new message instead of creating a new sub-case.",
                                    )
                                }
                            }
                            putArray("required").add("agentName").add("task")
                        }
                    }
                }
                putArray("required").add("delegations")
            }.let { objectMapper.writeValueAsString(it) }

    override suspend fun execute(
        input: Args?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.delegations.isEmpty()) {
            return ToolExecutionResult.error(
                output = "Delegation requires at least one entry in the delegations list.",
                errorType = "INVALID_INPUT",
            )
        }

        val invalidAgents = input.delegations.map { it.agentName }.filter { it !in allowedAgents }
        if (invalidAgents.isNotEmpty()) {
            return ToolExecutionResult.error(
                output =
                    "${invalidAgents.size} Agent(s) (${
                        invalidAgents.take(10).joinToString(", ") { "'$it'" }
                    }) are not in the delegation allowlist. " +
                        "Allowed agents: ${allowedAgents.joinToString(", ")}.",
                errorType = "UNAUTHORIZED_AGENT",
            )
        }

        val userId =
            context.userId
                ?: return ToolExecutionResult.error(
                    output = "Delegation requires a user context (userId is null).",
                    errorType = "MISSING_USER_CONTEXT",
                )
        val toolRequestId =
            context.toolRequestId
                ?: return ToolExecutionResult.error(
                    output = "Delegation requires the originating tool request id.",
                    errorType = "MISSING_TOOL_REQUEST_ID",
                )

        logger.info {
            "[DelegationTool] Launching ${input.delegations.size} delegation(s) in parallel " +
                "from parent case $parentCaseId (timeout=${timeoutMs}ms)"
        }

        // Run all delegations in parallel. Each delegation has its own timeout budget
        // so a single slow sub-case does not cancel the others — successful results are
        // preserved and only the timed-out ones are reported as failures.
        val results: List<DelegationResult> =
            coroutineScope {
                input.delegations
                    .map { delegation ->
                        async { runSingleDelegation(delegation, userId, toolRequestId) }
                    }.awaitAll()
            }

        val anySuccess = results.any { it.success }
        val output = objectMapper.writeValueAsString(results.map { it.toMap() })

        logger.info {
            "[DelegationTool] Batch complete: ${results.count { it.success }}/${results.size} succeeded"
        }

        return ToolExecutionResult(
            output = output,
            success = anySuccess,
        )
    }

    /**
     * Runs a single delegation: starts or resumes the sub-case, waits for completion,
     * loads events, and extracts the result. Never throws — errors are captured as
     * [DelegationResult.success] = false.
     *
     * The [timeoutMs] budget applies to this individual delegation. A timeout kills
     * the sub-case and returns a failure result, leaving sibling delegations unaffected.
     */
    private suspend fun runSingleDelegation(
        delegation: Delegation,
        userId: UUID,
        toolRequestId: String,
    ): DelegationResult {
        val delegationId = UUID.randomUUID()
        val agentName = delegation.agentName
        val resumed = delegation.subCaseId != null
        val subRuntime = runCatching {
            delegation.subCaseId?.let { subCaseId ->
                subCaseManager.resumeSubCase(subCaseId, agentName, delegation.task, userId, allowedAgents)
            } ?: subCaseManager.startSubCase(parentCaseId, namespaceId, agentName, delegation.task, userId)
        }.getOrElse { error ->
            logger.warn(error) { "[DelegationTool] Failed to start sub-case for '$agentName'" }
            return DelegationResult(agentName, delegation.subCaseId, delegationId, toolRequestId, false,
                error = "Failed to start sub-case: ${error.message}", errorType = "START_FAILED")
        }
        val subCaseId = subRuntime.id
        subCaseManager.emitParentEvent(
            SubCaseStartedEvent(namespaceId = namespaceId, caseId = parentCaseId, delegationId = delegationId,
                toolRequestId = toolRequestId, subCaseId = subCaseId, agentName = agentName, task = delegation.task, resumed = resumed),
        )

        var outcome = SubCaseOutcome.ERROR
        var finishErrorType: String? = "DELEGATION_ERROR"
        return try {
            val finalStatus = try {
                withTimeout(timeoutMs) { subRuntime.statusFlow.filter { it == CaseStatus.IDLE || it.isTerminal() }.first() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { subCaseManager.killCase(subCaseId) }
                outcome = SubCaseOutcome.TIMEOUT
                finishErrorType = "TIMEOUT"
                return DelegationResult(agentName, subCaseId, delegationId, toolRequestId, false,
                    error = "Sub-case timed out after ${timeoutMs / 1000}s.", errorType = finishErrorType)
            }
            if (finalStatus.isTerminal()) {
                outcome = if (finalStatus == CaseStatus.KILLED) SubCaseOutcome.KILLED else SubCaseOutcome.ERROR
                finishErrorType = if (outcome == SubCaseOutcome.KILLED) null else "DELEGATION_ERROR"
                return DelegationResult(agentName, subCaseId, delegationId, toolRequestId, false,
                    error = "Sub-case ended with status $finalStatus without producing a result.", errorType = finishErrorType)
            }
            val events = try {
                withTimeout(eventLoadTimeoutMs) { loadCaseEvents(subCaseId) }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                outcome = SubCaseOutcome.ERROR
                finishErrorType = "EVENT_LOAD_TIMEOUT"
                return DelegationResult(agentName, subCaseId, delegationId, toolRequestId, false,
                    error = "Sub-case completed but its event history could not be loaded in time.", errorType = finishErrorType)
            }
            val lastQuestion = events.filterIsInstance<QuestionEvent>().lastOrNull()
            val lastAgentMessage = events.filterIsInstance<MessageEvent>().lastOrNull { it.actor.role == ActorRole.AGENT }
            val waiting = lastQuestion != null && (lastAgentMessage == null || events.indexOf(lastQuestion) > events.indexOf(lastAgentMessage))
            if (waiting) {
                outcome = SubCaseOutcome.WAITING_USER
                finishErrorType = null
                DelegationResult(agentName, subCaseId, delegationId, toolRequestId, true,
                    pendingQuestion = lastQuestion.question, options = lastQuestion.options)
            } else {
                outcome = SubCaseOutcome.SUCCESS
                finishErrorType = null
                DelegationResult(agentName, subCaseId, delegationId, toolRequestId, true, result = extractLastAgentMessage(events))
            }
        } catch (error: Exception) {
            outcome = SubCaseOutcome.ERROR
            finishErrorType = "DELEGATION_ERROR"
            DelegationResult(agentName, subCaseId, delegationId, toolRequestId, false,
                error = "Delegation failed: ${error.message}", errorType = finishErrorType)
        } finally {
            subCaseManager.emitParentEvent(
                SubCaseFinishedEvent(namespaceId = namespaceId, caseId = parentCaseId, delegationId = delegationId,
                    toolRequestId = toolRequestId, subCaseId = subCaseId, agentName = agentName,
                    outcome = outcome, errorType = finishErrorType),
            )
        }
    }

    private fun extractLastAgentMessage(events: List<CaseEvent>): String =
        events
            .filterIsInstance<MessageEvent>()
            .lastOrNull { it.actor.role == ActorRole.AGENT }
            ?.content
            ?.filterIsInstance<MessageContent.Text>()
            ?.joinToString("\n") { it.content }
            ?.takeIf { it.isNotBlank() }
            ?: "Sub-agent completed the task but produced no text output."

    // -------------------------------------------------------------------------
    // Internal result model
    // -------------------------------------------------------------------------

    private data class DelegationResult(
        val agentName: String,
        val subCaseId: UUID?,
        val delegationId: UUID,
        val toolRequestId: String,
        val success: Boolean,
        val result: String? = null,
        val pendingQuestion: String? = null,
        val options: List<String>? = null,
        val error: String? = null,
        val errorType: String? = null,
    ) {
        fun toMap(): Map<String, Any?> =
            buildMap {
                put("agentName", agentName)
                put("subCaseId", subCaseId?.toString())
                put("delegationId", delegationId.toString())
                put("toolRequestId", toolRequestId)
                put("success", success)
                result?.let { put("result", it) }
                pendingQuestion?.let { put("pendingQuestion", it) }
                options?.let { put("options", it) }
                error?.let { put("error", it) }
                errorType?.let { put("errorType", it) }
            }
    }

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()
        private const val EVENT_LOAD_TIMEOUT_MS = 10_000L
    }
}
