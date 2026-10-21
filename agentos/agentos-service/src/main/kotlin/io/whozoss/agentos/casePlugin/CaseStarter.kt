package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.redirect.globToRegex
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * What the two starter tools ([FreeCaseStarterTool], [FreeCaseStarterForUserTool]) do the same way, and nothing
 * else: apply the agent allowlist of their integration config, read the caller and the calling case from the
 * [ToolContext], hand the request to [CaseStarterService] and turn its answer into a [ToolExecutionResult].
 *
 * It has no option: whatever differs between the tools (description, schema, validation of their own fields) is
 * written in each of them. Every rule on users (who may name another user, whether that user may be served) is
 * owned by [CaseStarterService], not here.
 *
 * Who is calling, and from which case, comes from the [ToolContext], never from what the LLM sent. A refusal of the
 * service ([CaseStarterRefusedException]) becomes the error result, its reason being the `errorType`; any other
 * failure is not caught: like any tool failure, it reaches the agent layer, which logs it and reports it to the LLM.
 *
 * @param allowedAgents Glob patterns (`*` wildcard, case-insensitive) of the agents the tools may start. An agent
 *   must match one of them and none of [deniedAgents].
 * @param deniedAgents Exact agent names (case-insensitive) that are never started.
 */
internal class CaseStarter(
    private val caseStarterService: CaseStarterService,
    allowedAgents: List<String>,
    deniedAgents: Set<String>,
) {
    private val allowedRegexes = allowedAgents.map { globToRegex(it) }
    private val deniedNames = deniedAgents.map { it.lowercase() }.toSet()

    private fun isAgentAllowed(agentName: String): Boolean =
        agentName.lowercase() !in deniedNames && allowedRegexes.any { it.matches(agentName) }

    /**
     * Starts the case, for [targetUserId] or, when it is null, for the caller. The arguments have been validated
     * by the tool that received them.
     */
    fun start(
        context: ToolContext,
        agentName: String,
        prompt: String,
        title: String?,
        sessionContext: Map<String, Any?>?,
        targetUserId: UUID?,
    ): ToolExecutionResult {
        val callerId =
            context.userId
                ?: return ToolExecutionResult.error(
                    "Starting a case requires a user context (userId is null).",
                    errorType = "MISSING_USER_CONTEXT",
                )
        val callerCaseId =
            context.caseId
                ?: return ToolExecutionResult.error(
                    "Starting a case requires the calling case (the tool context has no case events).",
                    errorType = "MISSING_CASE_CONTEXT",
                )
        if (!isAgentAllowed(agentName)) {
            return ToolExecutionResult.error(
                "Agent '$agentName' cannot be started by this tool.",
                errorType = "UNAUTHORIZED_AGENT",
            )
        }
        val request =
            CaseStarterRequest(
                callerUserId = callerId,
                callerNamespaceId = context.namespaceId,
                callerCaseId = callerCaseId,
                agentName = agentName,
                prompt = prompt,
                targetUserId = targetUserId,
                title = title,
                sessionContext = sessionContext,
            )
        return try {
            caseStarterService.start(request).toToolResult()
        } catch (refusal: CaseStarterRefusedException) {
            ToolExecutionResult.error(refusal.message, errorType = refusal.reason.name)
        }
    }

    private fun StartedCase.toToolResult(): ToolExecutionResult =
        ToolExecutionResult.success(
            "Case $caseId started for agent '$agentName'. " +
                "It runs in the background on its own; its result is not returned here.",
            metadata =
                mapOf(
                    "caseId" to caseId.toString(),
                    "userId" to userId.toString(),
                    "agentName" to agentName,
                ),
            structuredOutput = objectMapper.valueToTree(CaseStarterOutput(caseId = caseId)),
        )

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
