package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryTrustedHeaderSigner
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
 * and carried via the shared signed proxy-header contract. The retries endpoint
 * receives only retry business data.
 */
class FactoryRequestAgentRetryTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val trustedHeaderSigner: FactoryTrustedHeaderSigner,
) : StandardTool<FactoryRequestAgentRetryTool.Input> {
    data class Input(
        val workflowId: String,
        val stepId: String,
        val expectedRevision: Long,
        val reasonCode: String,
        val idempotencyKey: String? = null,
    )

    override val name = "FACTORY_WORKSTREAM__request_agent_retry"
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
        // Exact allowlist of the retries endpoint — unknown keys are rejected with
        // INVALID_RETRY_REQUEST; the model-authored idempotencyKey is never sent.
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "stepId" to input.stepId,
                    "expectedRevision" to input.expectedRevision,
                    "reasonCode" to input.reasonCode,
                ),
            )
        val workflowId = encode(input.workflowId)
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/retries")
            .post(body.toRequestBody("application/json".toMediaType()))
        val request = trustedHeaderSigner.sign(requestBuilder, context).getOrElse {
            return fail("TRUST_CONTEXT_UNAVAILABLE", it.message ?: "Trusted Factory context is unavailable.")
        }.build()
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
