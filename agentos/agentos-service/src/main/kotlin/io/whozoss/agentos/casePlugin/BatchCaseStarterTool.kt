package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import mu.KLogging
import java.util.UUID

/**
 * Fan-out tool for the AgentLoop: starts one case per user element in [Input.previousStepOutput],
 * which is the structured output of [io.whozoss.agentos.userGroup.ListAgentUsersTool] — a JSON array
 * where each element has at least a `userId` field.
 *
 * **Direct Kotlin call**: this tool calls [CaseStarterService] directly (not via JSON delegation
 * through [FreeCaseStarterForUserTool]) so that [CaseStarterRequest.translateFrom] and
 * [CaseStarterRequest.scheduledPromptId] are carried through without being silently dropped.
 * All authorization gates live inside [CaseStarterService] and are not bypassed.
 *
 * **One case per element, no deduplication**: a user reachable via multiple entities receives
 * one case per element. This is a deliberate product decision.
 *
 * **Cap**: when the array size exceeds [LimitsConfigProperties.agentLoopMaxItems], the tool
 * returns an error immediately without starting any case.
 *
 * **Optional entity context**: when an element carries both `entityId` and `entityType`, the
 * tool builds an `activeContext` session context for that case. Otherwise null is passed and
 * [CaseStarterService] resolves the user's own context normally.
 *
 * @param configName Integration config name, used as tool-name prefix.
 * @param caseStarterService Starts the cases. It reaches [io.whozoss.agentos.caseFlow.CaseService]
 *   through a `@Lazy` proxy, so no new Spring cycle is introduced.
 * @param limitsConfig Platform limits; [LimitsConfigProperties.agentLoopMaxItems] is the cap.
 */
class BatchCaseStarterTool(
    configName: String?,
    private val caseStarterService: CaseStarterService,
    private val limitsConfig: LimitsConfigProperties,
) : StandardTool<BatchCaseStarterTool.Input> {

    /**
     * @param agentName Name of the agent to start for each user.
     * @param prompt The prompt sent to the agent, on behalf of each user. Self-contained.
     * @param title Optional conversation title; derived from [prompt] when blank.
     * @param translateFrom BCP-47 source language of [prompt]/[title]. When non-null and the
     *   target user has a different `preferredLanguage`, both are translated. Null = no translation.
     * @param scheduledPromptId UUID string of the scheduled prompt that triggered this batch;
     *   written to [io.whozoss.agentos.caseFlow.Case.scheduledPromptId] on each started case.
     * @param previousStepOutput JSON array produced by the preceding step (typically
     *   `ListAgentUsersTool`). Each element must carry at least `userId` (a UUID string). Optional
     *   `entityId` + `entityType` fields trigger per-element `activeContext` injection.
     */
    data class Input(
        val agentName: String,
        val prompt: String,
        val title: String? = null,
        val translateFrom: String? = null,
        val scheduledPromptId: String? = null,
        val previousStepOutput: JsonNode? = null,
    )

    override val name: String = buildToolName(configName)
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        "Fan-out: starts one case per user entry in previousStepOutput (the output of ListAgentUsers). " +
            "Each element must have a userId field. The cases run in the background; this tool returns " +
            "a summary of started, refused and failed cases without waiting for results. " +
            "Fails loudly if the array exceeds the platform batch cap."

    override val inputSchema: String = INPUT_SCHEMA

    override fun outputSchema(): JsonNode = OUTPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.agentName.isBlank() || input.prompt.isBlank()) {
            return ToolExecutionResult.error("agentName and prompt are required.", errorType = "MISSING_INPUT")
        }

        val array = input.previousStepOutput
        if (array == null || !array.isArray || array.size() == 0) {
            return ToolExecutionResult.error(
                "previousStepOutput must be a non-empty JSON array.",
                errorType = "INVALID_INPUT",
            )
        }

        val cap = limitsConfig.agentLoopMaxItems
        if (array.size() > cap) {
            return ToolExecutionResult.error(
                "Batch size ${array.size()} exceeds the platform cap of $cap items. " +
                    "No cases were started. Reduce the audience or raise the cap.",
                errorType = "OVER_BATCH_CAP",
            )
        }

        val callerUserId =
            context.userId
                ?: return ToolExecutionResult.error(
                    "BatchCaseStarter requires a user context (userId is null).",
                    errorType = "MISSING_USER_CONTEXT",
                )
        val callerCaseId =
            context.caseId
                ?: return ToolExecutionResult.error(
                    "BatchCaseStarter requires the calling case (the tool context has no case id).",
                    errorType = "MISSING_CASE_CONTEXT",
                )

        val scheduledPromptUuid = input.scheduledPromptId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

        var started = 0
        var unknownUser = 0
        var noAgentAccess = 0
        var failed = 0
        val startedCaseIds = mutableListOf<UUID>()

        for (element in array) {
            val userIdStr = element.path("userId").asText(null)
            if (userIdStr.isNullOrBlank()) {
                logger.warn { "[BatchCaseStarter] element missing userId, skipping: $element" }
                failed++
                continue
            }
            val targetUserId =
                runCatching { UUID.fromString(userIdStr) }.getOrNull()
                    ?: run {
                        logger.warn { "[BatchCaseStarter] invalid userId '$userIdStr', skipping" }
                        failed++
                        continue
                    }

            val sessionContext = buildSessionContext(element)

            val request =
                CaseStarterRequest(
                    callerUserId = callerUserId,
                    callerNamespaceId = context.namespaceId,
                    callerCaseId = callerCaseId,
                    agentName = input.agentName,
                    prompt = input.prompt,
                    title = input.title,
                    targetUserId = targetUserId,
                    sessionContext = sessionContext,
                    translateFrom = input.translateFrom,
                    scheduledPromptId = scheduledPromptUuid,
                )

            try {
                val result = caseStarterService.start(request)
                started++
                startedCaseIds += result.caseId
                logger.debug { "[BatchCaseStarter] started case ${result.caseId} for user $targetUserId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CaseStarterRefusedException) {
                when (e.reason) {
                    CaseStarterRefusal.TARGET_USER_NOT_FOUND -> {
                        unknownUser++
                        logger.warn { "[BatchCaseStarter] user $targetUserId not found: ${e.message}" }
                    }
                    CaseStarterRefusal.AGENT_NOT_AVAILABLE,
                    CaseStarterRefusal.TARGET_NO_ACCESS,
                    CaseStarterRefusal.FORBIDDEN,
                    -> {
                        noAgentAccess++
                        logger.warn { "[BatchCaseStarter] refused for user $targetUserId (${e.reason}): ${e.message}" }
                    }
                    else -> {
                        failed++
                        logger.warn { "[BatchCaseStarter] unexpected refusal for user $targetUserId (${e.reason}): ${e.message}" }
                    }
                }
            } catch (e: Exception) {
                failed++
                logger.error(e) { "[BatchCaseStarter] unexpected failure for user $targetUserId" }
            }
        }

        val summary =
            "Batch complete: $started started, $unknownUser unknown users, $noAgentAccess no-access, $failed failed " +
                "(out of ${array.size()} elements)."
        logger.info { "[BatchCaseStarter] $summary" }

        val structuredOutput =
            objectMapper.createObjectNode().apply {
                put("started", started)
                put("unknownUser", unknownUser)
                put("noAgentAccess", noAgentAccess)
                put("failed", failed)
                set<JsonNode>(
                    "startedCaseIds",
                    objectMapper.createArrayNode().also { arr -> startedCaseIds.forEach { arr.add(it.toString()) } },
                )
            }

        return ToolExecutionResult.success(output = summary, structuredOutput = structuredOutput)
    }

    /**
     * Builds an `activeContext` session context when the element carries both `entityId` and `entityType`.
     * Returns null otherwise, letting [CaseStarterService] resolve the user's own context.
     */
    private fun buildSessionContext(element: JsonNode): Map<String, Any?>? {
        val entityId = element.path("entityId").asText(null)?.takeIf { it.isNotBlank() } ?: return null
        val entityType = element.path("entityType").asText(null)?.takeIf { it.isNotBlank() } ?: return null
        return mapOf(
            "activeContext" to
                listOf(
                    mapOf(
                        "type" to entityType,
                        "data" to mapOf("id" to entityId),
                    ),
                ),
        )
    }

    companion object : KLogging() {
        /** Bare tool name, before the integration config prefix. */
        const val TOOL_NAME = "BatchCaseStarter"

        fun buildToolName(configName: String?): String = configName?.let { "${it}__$TOOL_NAME" } ?: TOOL_NAME

        /** Whether [toolName] is a `BatchCaseStarter` tool, whatever its integration config. */
        fun isBatchCaseStarterTool(toolName: String): Boolean =
            toolName == TOOL_NAME || toolName.endsWith("__$TOOL_NAME")

        private val objectMapper = jacksonObjectMapper()

        val OUTPUT_SCHEMA: JsonNode =
            objectMapper.readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "started":      { "type": "integer", "description": "Number of cases successfully started." },
                    "unknownUser":  { "type": "integer", "description": "Elements whose userId was not found." },
                    "noAgentAccess": { "type": "integer", "description": "Elements where the agent is not deployed to the user or the user has no namespace access." },
                    "failed":       { "type": "integer", "description": "Elements that failed for other reasons (infrastructure, unexpected refusals)." },
                    "startedCaseIds": {
                      "type": "array",
                      "items": { "type": "string", "format": "uuid" },
                      "description": "UUIDs of the cases that were successfully started, in order."
                    }
                  },
                  "required": ["started", "unknownUser", "noAgentAccess", "failed", "startedCaseIds"]
                }
                """.trimIndent(),
            )

        private val INPUT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "agentName": {
                  "type": "string",
                  "description": "Name of the agent to start for each user."
                },
                "prompt": {
                  "type": "string",
                  "description": "Full, self-contained first message for the agent, sent on behalf of each user."
                },
                "title": {
                  "type": "string",
                  "description": "Conversation title. Omit to derive from prompt."
                },
                "previousStepOutput": {
                  "type": "array",
                  "description": "Array of user references from the preceding step (ListAgentUsers). Each element must have a userId field (UUID). Optional entityId and entityType fields trigger per-element activeContext injection.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "userId":     { "type": "string", "format": "uuid" },
                      "entityId":   { "type": "string" },
                      "entityType": { "type": "string" }
                    },
                    "required": ["userId"]
                  }
                }
              },
              "required": ["agentName", "prompt", "previousStepOutput"],
              "additionalProperties": false
            }
            """.trimIndent()
    }
}
