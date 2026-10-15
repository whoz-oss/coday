package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.redirect.globToRegex
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Tool that starts a new case and returns its id.
 *
 * Input: the agent (by name), the prompt (first message, sent on behalf of the user), an optional
 * title (name of the conversation), an optional context (what the new case is shown with its first
 * message), and, when the integration allows it, the user the case runs for. Output: the case id.
 *
 * The target agent runs in the background with its own conversation and the tool returns at once.
 * It does not wait for a result: that is what separates it from `DELEGATE__delegate`, which awaits
 * its sub-cases. The new case is a top-level case, not a sub-case: stopping the calling case does not
 * stop it. The calling case is only recorded as its origin
 * ([io.whozoss.agentos.caseFlow.Case.originCaseId]).
 *
 * The agent is designated by **name**, not by id: names are unique per scope and are what an
 * `@mention` resolves, whereas an id is an internal value an LLM could only guess.
 *
 * The case runs in the **same namespace** as the calling run, and for the **same user** unless
 * [allowOtherUsers] lets the caller name another one. Who is calling, and from which case, comes
 * from [ToolContext], never from the input: the calling case is [ToolContext.caseId], and a call
 * without one, such as a direct invocation through the API, is refused with `MISSING_CASE_CONTEXT`.
 * The calling case's session context is **not** inherited: [Input.context] is the only thing that
 * reaches the new case, so the caller hands over just the relevant part, typically the output of an
 * enrichment tool.
 *
 * The tool parses the input, applies the agent allowlist of its integration config, and hands the
 * rest to [CaseLaunchService] along with the [allowOtherUsers] switch of that config. The service owns
 * every rule on users: whether another user may be named at all, and whether that user exists, reads
 * the namespace and has the agent deployed. A failure of the service other than a refusal is not caught
 * here: like any tool failure, it reaches the agent layer, which logs it and reports it to the LLM.
 * Another caller (an AgentLoop step, say) can look the tool up by name and call [executeWithJson],
 * reading the case id from its structured output, so that the integration's config applies to it too.
 *
 * ## `userId`: the schema guides, the service enforces -- same pattern as `queryUser`
 *
 * `userId` only appears in [inputSchema] when [allowOtherUsers] is on. That *guides* the LLM; the
 * service, given the switch, is what *enforces*, because an LLM can and does send fields its schema
 * does not declare. The caller's own id designates the current user, and is accepted either way.
 *
 * @param configName The integration config name, used as tool-name prefix.
 * @param allowedAgents Glob patterns (`*` wildcard, case-insensitive) of the agents this instance
 *   may start. An agent must match one of them and none of [deniedAgents].
 * @param deniedAgents Exact agent names (case-insensitive) that are never started.
 * @param allowOtherUsers Whether the caller may start the case for a user other than itself. Handed
 *   to [CaseLaunchService], which enforces it.
 */
class CreateCaseTool(
    configName: String?,
    private val caseLaunchService: CaseLaunchService,
    private val allowedAgents: List<String> = listOf("*"),
    deniedAgents: Set<String> = emptySet(),
    private val allowOtherUsers: Boolean = false,
) : StandardTool<CreateCaseTool.Input> {
    /**
     * @param agentName Name of the agent that runs the new case.
     * @param prompt The first message the agent receives, sent on behalf of the user. Must be
     *   self-contained: the new case does not see the conversation it was started from.
     * @param title Name of the new conversation. Omit to derive it from the prompt.
     * @param userId Internal id of the user the case runs for. Only honoured when the integration
     *   allows it. Omit for the current user.
     * @param context Key/value context the new case starts with, for example the entity the prompt
     *   is about. Only these entries are passed on, never the calling case's own context.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val agentName: String,
        val prompt: String,
        val title: String? = null,
        val userId: String? = null,
        val context: Map<String, Any?>? = null,
    )

    private val allowedRegexes = allowedAgents.map { globToRegex(it) }
    private val deniedNames = deniedAgents.map { it.lowercase() }.toSet()

    private fun isAgentAllowed(agentName: String): Boolean =
        agentName.lowercase() !in deniedNames && allowedRegexes.any { it.matches(agentName) }

    override val name: String = buildToolName(configName)
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        buildString {
            append(
                "Start a new case: the given agent works on the prompt in the background, in its own " +
                    "conversation, and this tool returns immediately with the new case id without waiting " +
                    "for any result. Use it to hand off work that does not belong in the current conversation. " +
                    "The new case lives on its own: stopping the current case does not stop it. Unlike " +
                    "delegation, nothing waits for it and its result is never returned here. The prompt must be " +
                    "self-contained, the new case cannot see this conversation, and it does not inherit its " +
                    "context: pass in `context` only the part the agent needs. ",
            )
            append(
                if (allowOtherUsers) {
                    "The case runs in the current namespace, for the current user unless you pass userId " +
                        "(the user must have access to the namespace and the agent)."
                } else {
                    "The case runs for the current user, in the current namespace."
                },
            )
            if (allowedAgents != listOf("*")) {
                append(" Agents that can be started: ${allowedAgents.joinToString(", ")}.")
            }
        }

    override val inputSchema: String =
        objectMapper.writeValueAsString(
            objectMapper.createObjectNode().apply {
                put("type", "object")
                putObject("properties").apply {
                    putObject("agentName")
                        .put("type", "string")
                        .put("description", "Name of the agent that will work on the prompt.")
                    putObject("prompt")
                        .put("type", "string")
                        .put("description", "Full, self-contained first message for the agent, sent on behalf of the user.")
                    putObject("title")
                        .put("type", "string")
                        .put("description", "Name of the new conversation. Omit to derive it from the prompt.")
                    if (allowOtherUsers) {
                        putObject("userId")
                            .put("type", "string")
                            .put("format", "uuid")
                            .put(
                                "description",
                                "Id of the user the case runs for, as returned by another tool. " +
                                    "Omit for the current user.",
                            )
                    }
                    putObject("context").apply {
                        put("type", "object")
                        put(
                            "description",
                            "Optional key/value context the new case starts with, for example the entity the prompt " +
                                "is about. Pass only what the agent needs: nothing of the current context is carried over.",
                        )
                        put("additionalProperties", true)
                    }
                }
                putArray("required").add("agentName").add("prompt")
                put("additionalProperties", false)
            },
        )

    override fun outputSchema(): JsonNode = OUTPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.agentName.isBlank() || input.prompt.isBlank()) {
            return ToolExecutionResult.error("Both agentName and prompt are required.", errorType = "MISSING_INPUT")
        }
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
        if (!isAgentAllowed(input.agentName)) {
            return ToolExecutionResult.error(
                "Agent '${input.agentName}' cannot be started by this tool.",
                errorType = "UNAUTHORIZED_AGENT",
            )
        }
        val requestedUserId = input.userId?.takeIf { it.isNotBlank() }
        val targetUserId = requestedUserId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (requestedUserId != null && targetUserId == null) {
            return ToolExecutionResult.error("userId must be a valid UUID.", errorType = "INVALID_INPUT")
        }
        return caseLaunchService
            .launch(
                CaseLaunchRequest(
                    callerUserId = callerId,
                    callerNamespaceId = context.namespaceId,
                    callerCaseId = callerCaseId,
                    agentName = input.agentName,
                    task = input.prompt,
                    targetUserId = targetUserId,
                    title = input.title,
                    context = input.context,
                    allowOtherUsers = allowOtherUsers,
                ),
            ).toToolResult()
    }

    private fun CaseLaunchOutcome.toToolResult(): ToolExecutionResult =
        when (this) {
            is CaseLaunchOutcome.Refused -> ToolExecutionResult.error(message, errorType = reason.name)
            is CaseLaunchOutcome.Launched ->
                ToolExecutionResult.success(
                    "Case $caseId started for agent '$agentName'. " +
                        "It runs in the background on its own; its result is not returned here.",
                    metadata =
                        mapOf(
                            "caseId" to caseId.toString(),
                            "userId" to userId.toString(),
                            "agentName" to agentName,
                        ),
                    structuredOutput = objectMapper.createObjectNode().put("caseId", caseId.toString()),
                )
        }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        /** The whole output of the tool: the id of the case it started. */
        private val OUTPUT_SCHEMA: JsonNode =
            objectMapper.readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "caseId": { "type": "string", "format": "uuid", "description": "Id of the case that was started." }
                  },
                  "required": ["caseId"]
                }
                """.trimIndent(),
            )

        /** Name of the tool, bare or after the `__` that follows its integration config name. */
        const val TOOL_NAME = "CreateCase"

        fun buildToolName(configName: String?): String = configName?.let { "${it}__$TOOL_NAME" } ?: TOOL_NAME

        /** Whether [toolName] is a `CreateCase` tool, whatever its integration config. */
        fun isCreateCaseTool(toolName: String): Boolean = toolName == TOOL_NAME || toolName.endsWith("__$TOOL_NAME")
    }
}
