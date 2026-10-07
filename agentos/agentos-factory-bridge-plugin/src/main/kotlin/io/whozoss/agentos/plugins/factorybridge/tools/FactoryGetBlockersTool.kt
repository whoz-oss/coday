package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 6 read-only Workstream Agent tool: the active blockers of one
 * workflow — human gates, worker questions, failed oracles/verification,
 * blocked steps/environment and indeterminate runtime.
 *
 * Pure read of the authoritative `GET /api/factory/workflows/{workflowId}/actions`
 * endpoint: the blockers are calculated by the Factory and returned verbatim —
 * the tool never recomputes state and never mutates. `namespaceId` is injected
 * from the trusted [ToolContext].
 */
class FactoryGetBlockersTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetBlockersTool.Input> {
    data class Input(val workflowId: String)

    override val name = "FACTORY_WORKSTREAM__get_blockers"
    override val description =
        "List the active blockers of a Factory workflow (human gates, failed verification, blocked steps, " +
            "indeterminate runtime) as calculated by the Factory. Read-only."
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
            return FactoryReadSupport.errorResult(objectMapper, body, "Factory rejected the blockers read.")
        }
        val root =
            FactoryReadSupport.parseJson(objectMapper, body)
                ?: return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        val blockers = root.path("data").path("blockers")
        if (!blockers.isArray) {
            return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val output =
            blockers.take(MAX_BLOCKERS).map { blocker ->
                mapOf(
                    "code" to blocker.path("code").asText(),
                    "stepId" to blocker.path("stepId").takeIf { it.isTextual }?.asText(),
                    "message" to blocker.path("message").asText(),
                )
            }
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(output),
            metadata = mapOf("count" to output.size, "truncated" to (blockers.size() > output.size)),
        )
    }

    private companion object {
        const val MAX_BLOCKERS = 50
    }
}
