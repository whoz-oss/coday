package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * Phase 7 command tool: request a governed retry of a blocked workflow step.
 *
 * The agent only *requests* the retry — the Factory validates it under the revision
 * fence and opens a `retry` human interaction (`pending-human`): budgets and policy
 * stay with the control plane. The agent never picks an arbitrary `caseId` or
 * capability; the whole execution identity is derived from the trusted [ToolContext]
 * and carried via `x-factory-*` trust headers plus the accepted body `namespaceId`
 * hint. The retries endpoint rejects any other body key.
 */
@Deprecated(
    "Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md",
    level = DeprecationLevel.WARNING,
)
class FactoryRequestAgentRetryTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryRequestAgentRetryTool.Input> {
    data class Input(
        val workflowId: String,
        val stepId: String,
        val expectedRevision: Long,
        val reasonCode: String,
        val idempotencyKey: String? = null,
    )

    override val name = "FACTORY__request_agent_retry"
    override val description =
        "Request a governed retry of a blocked workflow step. This only opens a retry request subject to " +
            "budgets, revision fencing and human approval — the agent never decides the retry itself."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"stepId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"reasonCode":{"type":"string","maxLength":64},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","stepId","expectedRevision","reasonCode"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return fail("INVALID_RETRY_REQUEST", "workflowId, stepId, expectedRevision and reasonCode are required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return fail("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return fail("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return fail("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        // Exact allowlist of the retries endpoint — unknown keys are rejected with
        // INVALID_RETRY_REQUEST; the model-authored idempotencyKey is never sent.
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "namespaceId" to context.namespaceId.toString(),
                    "stepId" to input.stepId,
                    "expectedRevision" to input.expectedRevision,
                    "reasonCode" to input.reasonCode,
                ),
            )
        val workflowId = encode(input.workflowId)
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/retries")
                .header("x-factory-namespace-id", context.namespaceId.toString())
                .header("x-factory-runtime-id", runtimeId)
                .header("x-factory-agent-id", agent)
                .header("x-factory-case-id", caseIds.single().toString())
                .header("x-factory-actor-id", actor)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val root =
                        try {
                            objectMapper.readTree(response.body?.string())
                        } catch (_: Exception) {
                            return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                        }
                    if (!response.isSuccessful) {
                        return@use fail(
                            root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                            root.path("error").path("message").asText("Factory rejected the retry request."),
                        )
                    }
                    val data = root.path("data")
                    val interaction = data.path("interaction")
                    val interactionId = interaction.path("interactionId").takeIf { it.isTextual }?.asText()
                    val revision = interaction.path("revision").takeIf { it.isIntegralNumber }?.asLong()
                    if (interactionId == null || revision == null) {
                        return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory response missing interaction id or revision.")
                    }
                    val allowedActions = fetchAllowedActions(input.workflowId, context)
                    val output =
                        linkedMapOf<String, Any?>(
                            "status" to "pending-human",
                            "revision" to revision,
                            "reasonCode" to "retry_requested",
                            "interactionId" to interactionId,
                            "proposalId" to null,
                            "allowedActions" to allowedActions,
                            "message" to "Retry requested for step ${input.stepId}; awaiting human approval.",
                            "workflowId" to data.path("workflowId").asText(input.workflowId),
                            "stepId" to interaction.path("stepId").asText(input.stepId),
                        )
                    ToolExecutionResult.success(objectMapper.writeValueAsString(output))
                }
            }
        } catch (_: SocketTimeoutException) {
            fail("FACTORY_TIMEOUT", "Factory call timed out.")
        } catch (_: Exception) {
            fail("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    /** Best-effort enrichment: a failed actions read never fails the command. */
    private fun fetchAllowedActions(
        workflowId: String,
        context: ToolContext,
    ): JsonNode =
        try {
            val url =
                "${baseUrl.trimEnd('/')}/api/factory/workflows/${encode(workflowId)}/actions" +
                    "?namespaceId=${encode(context.namespaceId.toString())}"
            httpClient
                .newCall(Request.Builder().url(url).get().build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use objectMapper.createArrayNode()
                    val root = objectMapper.readTree(response.body?.string())
                    root.path("data").path("allowedActions").takeIf { it.isArray }
                        ?: objectMapper.createArrayNode()
                }
        } catch (_: Exception) {
            objectMapper.createArrayNode()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun fail(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
