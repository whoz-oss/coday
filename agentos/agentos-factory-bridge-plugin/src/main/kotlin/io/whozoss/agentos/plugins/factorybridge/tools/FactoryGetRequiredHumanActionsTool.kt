package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 6 read-only Workstream Agent tool: the human actions currently
 * required on one workflow, restricted to the interactions the current
 * actor/context is authorized to respond to.
 *
 * Pure read of the authoritative `GET /api/factory/workflows/{workflowId}/actions`
 * endpoint: the Factory only emits `reply` allowed-actions for callers it deems
 * authorized (verified human principal), so the tool simply projects them — it
 * never recomputes state and never mutates. When the caller is not an
 * authorized human the Factory emits no `reply` action and the tool returns
 * `[]`. `namespaceId` is injected from the trusted [ToolContext].
 */
class FactoryGetRequiredHumanActionsTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetRequiredHumanActionsTool.Input> {
    data class Input(val workflowId: String)

    override val name = "FACTORY__get_required_human_actions"
    override val description =
        "List the pending human decisions of a Factory workflow that the current actor is authorized to answer " +
            "(Factory-calculated reply actions only). Read-only."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128}},"required":["workflowId"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val workflowId = input?.workflowId
        if (workflowId == null || !FactoryReadSupport.WORKFLOW_ID.matches(workflowId)) {
            return FactoryReadSupport.failure("INVALID_WORKFLOW_ID", "workflowId is invalid.")
        }
        val encoded = FactoryReadSupport.encodePathSegment(workflowId)
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded/actions?namespaceId=${context.namespaceId}")
                .get()
                .build()
        return FactoryReadSupport.executeGet(httpClient, request) { status, body -> parseResponse(status, body) }
    }

    internal fun parseResponse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        if (status !in 200..299) {
            return FactoryReadSupport.errorResult(objectMapper, body, "Factory rejected the human actions read.")
        }
        val root =
            FactoryReadSupport.parseJson(objectMapper, body)
                ?: return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        val allowedActions = root.path("data").path("allowedActions")
        if (!allowedActions.isArray) {
            return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        // Only `reply` actions — already restricted by the Factory to callers
        // authorized to respond. The two-option approve/reject action list is
        // synthesized from the stable contract; the prompt is the Factory label.
        val replies = allowedActions.filter { it.path("type").asText() == REPLY_ACTION_TYPE }
        val output =
            replies.take(MAX_ACTIONS).map { action ->
                mapOf(
                    "interactionId" to action.path("interactionId").asText(),
                    "stepId" to action.path("stepId").takeIf { it.isTextual }?.asText(),
                    "questionEventId" to action.path("questionEventId").takeIf { it.isTextual }?.asText(),
                    "prompt" to action.path("label").takeIf { it.isTextual }?.asText(),
                    "actions" to
                        listOf(
                            mapOf("id" to "approve", "label" to "Approve"),
                            mapOf("id" to "reject", "label" to "Reject"),
                        ),
                    "expectedRevision" to action.path("expectedRevision").asInt(),
                )
            }
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(output),
            metadata = mapOf("count" to output.size, "truncated" to (replies.size > output.size)),
        )
    }

    private companion object {
        const val MAX_ACTIONS = 50
        const val REPLY_ACTION_TYPE = "reply"
    }
}
