package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.redirect.globToRegex
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.tool.RequiresExplicitGrant
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.workflow.WorkflowTarget
import io.whozoss.agentos.workflow.WorkflowTargets
import kotlinx.coroutines.CancellationException
import mu.KLogging
import java.util.UUID

/**
 * Step 2 of every scheduler [io.whozoss.agentos.workflow.Workflow]: starts one case per element of
 * [Input.targets], the targets produced by step 1 (contract: [WorkflowTargets]).
 *
 * Per target:
 * 1. the user is resolved from [WorkflowTarget.userExternalId]; unknown → counted `unknownUser`;
 * 2. when the target carries `entityId` + `entityType`, the case gets an `activeContext` session
 *    context naming that entity;
 * 3. when [Input.runId] is set, a [DispatchKey] makes the delivery idempotent (re-running a run never
 *    starts the same case twice; duplicates are counted `skipped`);
 * 4. [CaseStarterService.start] is called directly (not through `FreeCaseStarterForUser` JSON) so the
 *    backend-only fields reach it. Every authorization gate lives there and is not bypassed.
 *
 * One case per element, no deduplication: a user reached through several entities receives one case
 * per entity (product decision).
 *
 * **Cap**: more targets than [LimitsConfigProperties.agentLoopMaxItems] fails the call before any case
 * is started, never truncates.
 *
 * **Grant**: [RequiresExplicitGrant]: an agent receives this tool only by naming it in its `CASE`
 * integration (`CASE: [BatchCaseStarter]`). The integration's `agents` / `deniedAgents` allowlist
 * applies, as for the other starters.
 *
 * @param configName Integration config name, used as tool-name prefix.
 * @param caseStarterService Starts the cases (reaches `CaseService` through a `@Lazy` proxy).
 * @param userService Resolves target users by external id.
 * @param limitsConfig Platform limits; [LimitsConfigProperties.agentLoopMaxItems] is the cap.
 */
class BatchCaseStarterTool(
    configName: String?,
    private val caseStarterService: CaseStarterService,
    private val userService: UserService,
    private val limitsConfig: LimitsConfigProperties,
    allowedAgents: List<String> = listOf("*"),
    deniedAgents: Set<String> = emptySet(),
) : StandardTool<BatchCaseStarterTool.Input>,
    RequiresExplicitGrant {
    private val allowedRegexes = allowedAgents.map { globToRegex(it) }
    private val deniedNames = deniedAgents.map { it.lowercase() }.toSet()

    /**
     * @param agentName Name of the agent each case is started with.
     * @param targets Who to start a case for: the step-1 targets ([WorkflowTargets]).
     * @param prompt First message, sent on behalf of each user. Required unless [promptId] is set.
     * @param promptId Stored [io.whozoss.agentos.prompt.Prompt] supplying content and title, translated
     *   per user (persisted translations reused). Takes precedence over [prompt] and [translateFrom].
     * @param title Conversation title; derived from the prompt when blank.
     * @param translateFrom Source language of [prompt]/[title]; when set they are translated to each
     *   user's language. Ignored with [promptId].
     * @param scheduledPromptId Recorded on each case ([io.whozoss.agentos.caseFlow.Case.scheduledPromptId]).
     * @param runId Scheduler run; when set, enables [DispatchKey] idempotency.
     */
    data class Input(
        val agentName: String,
        val targets: List<WorkflowTarget>? = null,
        val prompt: String? = null,
        val promptId: String? = null,
        val title: String? = null,
        val translateFrom: String? = null,
        val scheduledPromptId: String? = null,
        val runId: String? = null,
    )

    /** Per-target outcome, aggregated in the tool result. */
    private enum class Outcome { STARTED, SKIPPED, UNKNOWN_USER, NO_AGENT_ACCESS, FAILED }

    override val name: String = buildToolName(configName)
    override val version: String = "2.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        "Starts one case per element of `targets` (users, optionally with the entity the case is about). " +
            "The cases run in the background; this tool returns a summary of started, skipped, refused and failed " +
            "targets without waiting for results. Fails, starting nothing, if there are more targets than the platform cap."

    override val inputSchema: String = INPUT_SCHEMA

    override fun outputSchema(): JsonNode = OUTPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.agentName.isBlank()) {
            return ToolExecutionResult.error("agentName is required.", errorType = "MISSING_INPUT")
        }
        if (input.agentName.lowercase() in deniedNames || allowedRegexes.none { it.matches(input.agentName) }) {
            return ToolExecutionResult.error("Agent '${input.agentName}' cannot be started by this tool.", errorType = "UNAUTHORIZED_AGENT")
        }
        if (input.prompt.isNullOrBlank() && input.promptId.isNullOrBlank()) {
            return ToolExecutionResult.error("Either a non-blank prompt or a non-blank promptId is required.", errorType = "MISSING_INPUT")
        }
        val targets = input.targets
        if (targets.isNullOrEmpty()) {
            return ToolExecutionResult.error("targets must be a non-empty array.", errorType = "INVALID_INPUT")
        }
        val cap = limitsConfig.agentLoopMaxItems
        if (targets.size > cap) {
            return ToolExecutionResult.error(
                "${targets.size} targets exceed the platform cap of $cap. No case was started. Reduce the audience or raise the cap.",
                errorType = "OVER_BATCH_CAP",
            )
        }
        val promptId = parseOptionalUuid("promptId", input.promptId).getOrElse { return it.asInvalidInput() }
        val scheduledPromptId = parseOptionalUuid("scheduledPromptId", input.scheduledPromptId).getOrElse { return it.asInvalidInput() }
        val runId = parseOptionalUuid("runId", input.runId).getOrElse { return it.asInvalidInput() }
        val callerUserId =
            context.userId
                ?: return ToolExecutionResult.error("BatchCaseStarter requires a user context.", errorType = "MISSING_USER_CONTEXT")
        val callerCaseId =
            context.caseId
                ?: return ToolExecutionResult.error("BatchCaseStarter requires the calling case.", errorType = "MISSING_CASE_CONTEXT")

        val startedCaseIds = mutableListOf<UUID>()
        val outcomes =
            targets.map { target ->
                startOne(target) { targetUserId ->
                    CaseStarterRequest(
                        callerUserId = callerUserId,
                        callerNamespaceId = context.namespaceId,
                        callerCaseId = callerCaseId,
                        agentName = input.agentName,
                        prompt = input.prompt.orEmpty(),
                        title = input.title,
                        targetUserId = targetUserId,
                        sessionContext = activeContextOf(target),
                        translateFrom = input.translateFrom,
                        scheduledPromptId = scheduledPromptId,
                        dispatchKey = runId?.let { dispatchKeyOf(it, targetUserId, target) },
                        promptId = promptId,
                    )
                }.also { (outcome, caseId) -> if (outcome == Outcome.STARTED && caseId != null) startedCaseIds += caseId }
                    .first
            }
        return result(outcomes.groupingBy { it }.eachCount(), targets.size, startedCaseIds)
    }

    /** Resolves the target user, starts the case, and classifies what happened. Never throws but on cancellation. */
    private fun startOne(
        target: WorkflowTarget,
        request: (targetUserId: UUID) -> CaseStarterRequest,
    ): Pair<Outcome, UUID?> {
        val user =
            userService.findByExternalId(target.userExternalId)
                ?: return (Outcome.UNKNOWN_USER to null).also {
                    logger.warn { "[BatchCaseStarter] no user with externalId '${target.userExternalId}'" }
                }
        return try {
            Outcome.STARTED to caseStarterService.start(request(user.id)).caseId
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaseStarterRefusedException) {
            logger.info { "[BatchCaseStarter] refused for user ${user.id} (${e.reason}): ${e.message}" }
            outcomeOf(e.reason) to null
        } catch (e: Exception) {
            logger.error(e) { "[BatchCaseStarter] unexpected failure for user ${user.id}" }
            Outcome.FAILED to null
        }
    }

    private fun outcomeOf(refusal: CaseStarterRefusal): Outcome =
        when (refusal) {
            CaseStarterRefusal.ALREADY_DISPATCHED -> Outcome.SKIPPED
            CaseStarterRefusal.TARGET_USER_NOT_FOUND -> Outcome.UNKNOWN_USER
            CaseStarterRefusal.AGENT_NOT_AVAILABLE,
            CaseStarterRefusal.TARGET_NO_ACCESS,
            CaseStarterRefusal.FORBIDDEN,
            -> Outcome.NO_AGENT_ACCESS
            else -> Outcome.FAILED
        }

    private fun dispatchKeyOf(
        runId: UUID,
        userId: UUID,
        target: WorkflowTarget,
    ): String = target.entityId?.takeIf { it.isNotBlank() }?.let { DispatchKey.of(runId, userId, it) } ?: DispatchKey.of(runId, userId)

    /** The `activeContext` naming the target's entity, or null when the target has none. */
    private fun activeContextOf(target: WorkflowTarget): Map<String, Any?>? {
        val entityId = target.entityId?.takeIf { it.isNotBlank() } ?: return null
        val entityType = target.entityType?.takeIf { it.isNotBlank() } ?: return null
        return mapOf("activeContext" to listOf(mapOf("type" to entityType, "data" to mapOf("id" to entityId))))
    }

    private fun result(
        counts: Map<Outcome, Int>,
        total: Int,
        startedCaseIds: List<UUID>,
    ): ToolExecutionResult {
        fun count(outcome: Outcome) = counts[outcome] ?: 0
        val summary =
            "Batch complete: ${count(Outcome.STARTED)} started, ${count(Outcome.SKIPPED)} skipped (already dispatched), " +
                "${count(Outcome.UNKNOWN_USER)} unknown users, ${count(Outcome.NO_AGENT_ACCESS)} no-access, " +
                "${count(Outcome.FAILED)} failed (out of $total targets)."
        logger.info { "[BatchCaseStarter] $summary" }
        val structuredOutput =
            objectMapper.valueToTree<JsonNode>(
                mapOf(
                    "started" to count(Outcome.STARTED),
                    "skipped" to count(Outcome.SKIPPED),
                    "unknownUser" to count(Outcome.UNKNOWN_USER),
                    "noAgentAccess" to count(Outcome.NO_AGENT_ACCESS),
                    "failed" to count(Outcome.FAILED),
                    "startedCaseIds" to startedCaseIds.map { it.toString() },
                ),
            )
        return ToolExecutionResult.success(output = summary, structuredOutput = structuredOutput)
    }

    /** Null when [value] is null or blank; a failure naming [field] when it is not a UUID. */
    private fun parseOptionalUuid(
        field: String,
        value: String?,
    ): Result<UUID?> {
        val text = value?.takeIf { it.isNotBlank() } ?: return Result.success(null)
        return runCatching { UUID.fromString(text) }
            .recoverCatching { throw IllegalArgumentException("$field '$text' is not a valid UUID.") }
    }

    private fun Throwable.asInvalidInput(): ToolExecutionResult = ToolExecutionResult.error(message ?: "Invalid input.", errorType = "INVALID_INPUT")

    companion object : KLogging() {
        /** Bare tool name, before the integration config prefix. */
        const val TOOL_NAME = "BatchCaseStarter"

        fun buildToolName(configName: String?): String = configName?.let { "${it}__$TOOL_NAME" } ?: TOOL_NAME

        private val objectMapper = jacksonObjectMapper()

        val OUTPUT_SCHEMA: JsonNode =
            objectMapper.readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "started":       { "type": "integer", "description": "Cases started." },
                    "skipped":       { "type": "integer", "description": "Targets already delivered by this run (idempotency)." },
                    "unknownUser":   { "type": "integer", "description": "Targets whose userExternalId matches no user." },
                    "noAgentAccess": { "type": "integer", "description": "Targets refused: agent not deployed to the user, user without namespace access, or caller not allowed." },
                    "failed":        { "type": "integer", "description": "Targets that failed for another reason." },
                    "startedCaseIds": { "type": "array", "items": { "type": "string", "format": "uuid" } }
                  },
                  "required": ["started", "skipped", "unknownUser", "noAgentAccess", "failed", "startedCaseIds"]
                }
                """.trimIndent(),
            )

        private val INPUT_SCHEMA: String =
            objectMapper.writeValueAsString(
                objectMapper.readTree(
                    """
                    {
                      "type": "object",
                      "description": "Meant for backend workflows: granted only when an agent's CASE integration names it explicitly.",
                      "properties": {
                        "agentName":         { "type": "string", "description": "Agent each case is started with." },
                        "prompt":            { "type": "string", "description": "First message, sent on behalf of each user. Required unless promptId is given." },
                        "promptId":          { "type": "string", "format": "uuid", "description": "Stored prompt, translated to each user's language. Takes precedence over prompt." },
                        "title":             { "type": "string", "description": "Conversation title. Omit to derive it from the prompt." },
                        "translateFrom":     { "type": "string", "description": "Language of prompt and title (BCP-47). Ignored with promptId." },
                        "scheduledPromptId": { "type": "string", "format": "uuid", "description": "Scheduled prompt recorded on each case." },
                        "runId":             { "type": "string", "format": "uuid", "description": "Run that triggered the batch; makes delivery idempotent." }
                      },
                      "required": ["agentName", "${WorkflowTargets.FIELD}"],
                      "additionalProperties": false
                    }
                    """.trimIndent(),
                ).also { schema ->
                    (schema["properties"] as com.fasterxml.jackson.databind.node.ObjectNode).set<JsonNode>(
                        WorkflowTargets.FIELD,
                        WorkflowTargets.ARRAY_SCHEMA,
                    )
                },
            )
    }
}
