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

/** Delegates independent tasks to child cases and records durable parent-case lifecycle events. */
class DelegationTool(
    private val subCaseManager: SubCaseManager,
    private val parentCaseId: UUID,
    private val namespaceId: UUID,
    private val allowedAgents: List<String>,
    private val loadCaseEvents: suspend (UUID) -> List<CaseEvent>,
    private val timeoutMs: Long = 5 * 60 * 1_000L,
    private val eventLoadTimeoutMs: Long = EVENT_LOAD_TIMEOUT_MS,
) : StandardTool<DelegationTool.Args> {
    data class Delegation(val agentName: String, val task: String, val subCaseId: UUID? = null)
    data class Args(val delegations: List<Delegation>)

    override val name = "DELEGATE__delegate"
    override val description = "Delegate one or more self-contained tasks to sub-agents. All delegations run in parallel. Available agents: ${allowedAgents.joinToString(", ")}."
    override val version = "1.0.0"
    override val paramType: Class<Args> = Args::class.java
    override val inputSchema: String = objectMapper.writeValueAsString(
        objectMapper.createObjectNode().apply {
            put("type", "object")
            putObject("properties").putObject("delegations").apply {
                put("type", "array")
                putObject("items").apply {
                    put("type", "object")
                    putObject("properties").apply {
                        putObject("agentName").put("type", "string").putArray("enum").also { allowedAgents.forEach(it::add) }
                        putObject("task").put("type", "string")
                        putObject("subCaseId").put("type", "string").put("format", "uuid")
                    }
                    putArray("required").add("agentName").add("task")
                }
            }
            putArray("required").add("delegations")
        },
    )

    override suspend fun execute(input: Args?, context: ToolContext): ToolExecutionResult {
        if (input == null || input.delegations.isEmpty()) return ToolExecutionResult.error(output = "Delegation requires at least one entry in the delegations list.", errorType = "INVALID_INPUT")
        val invalidAgents = input.delegations.map { it.agentName }.filter { it !in allowedAgents }
        if (invalidAgents.isNotEmpty()) return ToolExecutionResult.error(output = "Agent(s) ${invalidAgents.joinToString()} are not in the delegation allowlist.", errorType = "UNAUTHORIZED_AGENT")
        val userId = context.userId ?: return ToolExecutionResult.error(output = "Delegation requires a user context (userId is null).", errorType = "MISSING_USER_CONTEXT")
        val toolRequestId = context.toolRequestId
            ?: return ToolExecutionResult.error(output = "Delegation requires the parent tool request id.", errorType = "MISSING_TOOL_REQUEST_ID")

        val results = coroutineScope { input.delegations.map { async { runSingleDelegation(it, userId, toolRequestId, UUID.randomUUID()) } }.awaitAll() }
        return ToolExecutionResult(output = objectMapper.writeValueAsString(results.map { it.toMap() }), success = results.any { it.success })
    }

    private suspend fun runSingleDelegation(delegation: Delegation, userId: UUID, toolRequestId: String, delegationId: UUID): DelegationResult {
        val resumed = delegation.subCaseId != null
        val runtime = runCatching {
            if (resumed) subCaseManager.resumeSubCase(delegation.subCaseId!!, delegation.agentName, delegation.task, userId, allowedAgents)
            else subCaseManager.startSubCase(parentCaseId, namespaceId, delegation.agentName, delegation.task, userId)
        }.getOrElse { error ->
            return DelegationResult(delegationId, toolRequestId, delegation.agentName, delegation.subCaseId, false, error = "Failed to start sub-case: ${error.message}", errorType = "START_FAILED")
        }
        val subCaseId = runtime.id
        subCaseManager.emitParentEvent(SubCaseStartedEvent(namespaceId = namespaceId, caseId = parentCaseId, delegationId = delegationId, toolRequestId = toolRequestId, subCaseId = subCaseId, agentName = delegation.agentName, task = delegation.task, resumed = resumed))

        val result = try {
            val status = withTimeout(timeoutMs) { runtime.statusFlow.filter { it == CaseStatus.IDLE || it.isTerminal() }.first() }
            val events = try { withTimeout(eventLoadTimeoutMs) { loadCaseEvents(subCaseId) } } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                return finish(DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, false, error = "Sub-case completed but its event history could not be loaded in time.", errorType = "EVENT_LOAD_TIMEOUT"), SubCaseOutcome.ERROR)
            }
            if (status.isTerminal()) {
                val outcome = if (status == CaseStatus.KILLED) SubCaseOutcome.KILLED else SubCaseOutcome.ERROR
                DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, false, error = "Sub-case ended with status $status without producing a result.", errorType = "TERMINAL_STATUS") to outcome
            } else {
                val lastQuestion = events.filterIsInstance<QuestionEvent>().lastOrNull()
                val lastMessage = events.filterIsInstance<MessageEvent>().lastOrNull { it.actor.role == ActorRole.AGENT }
                if (lastQuestion != null && (lastMessage == null || events.indexOf(lastQuestion) > events.indexOf(lastMessage))) {
                    DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, true, pendingQuestion = lastQuestion.question, options = lastQuestion.options) to SubCaseOutcome.WAITING_USER
                } else {
                    DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, true, result = extractLastAgentMessage(events)) to SubCaseOutcome.SUCCESS
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { subCaseManager.killCase(subCaseId) }
            DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, false, error = "Sub-case timed out after ${timeoutMs / 1000}s.", errorType = "TIMEOUT") to SubCaseOutcome.TIMEOUT
        } catch (error: Exception) {
            logger.warn(error) { "[DelegationTool] Sub-case $subCaseId failed while awaiting its outcome" }
            DelegationResult(delegationId, toolRequestId, delegation.agentName, subCaseId, false, error = "Sub-case failed: ${error.message}", errorType = "DELEGATION_ERROR") to SubCaseOutcome.ERROR
        }
        return finish(result.first, result.second)
    }

    private fun finish(result: DelegationResult, outcome: SubCaseOutcome): DelegationResult {
        result.subCaseId?.let { subCaseManager.emitParentEvent(SubCaseFinishedEvent(namespaceId = namespaceId, caseId = parentCaseId, delegationId = result.delegationId, toolRequestId = result.toolRequestId, subCaseId = it, agentName = result.agentName, outcome = outcome, errorType = result.errorType)) }
        return result
    }

    private fun extractLastAgentMessage(events: List<CaseEvent>): String = events.filterIsInstance<MessageEvent>().lastOrNull { it.actor.role == ActorRole.AGENT }?.content?.filterIsInstance<MessageContent.Text>()?.joinToString("\n") { it.content }?.takeIf { it.isNotBlank() } ?: "Sub-agent completed the task but produced no text output."

    private data class DelegationResult(val delegationId: UUID, val toolRequestId: String, val agentName: String, val subCaseId: UUID?, val success: Boolean, val result: String? = null, val pendingQuestion: String? = null, val options: List<String>? = null, val error: String? = null, val errorType: String? = null) {
        fun toMap(): Map<String, Any?> = buildMap {
            put("delegationId", delegationId.toString()); put("toolRequestId", toolRequestId); put("agentName", agentName); put("subCaseId", subCaseId?.toString()); put("success", success)
            result?.let { put("result", it) }; pendingQuestion?.let { put("pendingQuestion", it) }; options?.let { put("options", it) }; error?.let { put("error", it) }; errorType?.let { put("errorType", it) }
        }
    }

    companion object : KLogging() { private val objectMapper = jacksonObjectMapper(); private const val EVENT_LOAD_TIMEOUT_MS = 10_000L }
}
