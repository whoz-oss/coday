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
 * Phase 7 command tool: explicitly interrupt (cancel) a durable agent attempt.
 *
 * The command is revision-fenced and subject to Factory permissions; the bridge
 * cancellation service performs the interrupt/kill + reconcile and moves the attempt
 * to the terminal `interrupted` status. The whole execution identity is derived from
 * the trusted [ToolContext] (`x-factory-*` trust headers plus the accepted body
 * `namespaceId` hint) — the model only supplies the bounded `workflowId`/`attemptId`
 * path references and the command payload. The cancel endpoint rejects any body key
 * outside `namespaceId|expectedRevision|reason`.
 */
@Deprecated(
    "Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md",
    level = DeprecationLevel.WARNING,
)
class FactoryInterruptAttemptTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryInterruptAttemptTool.Input> {
    data class Input(
        val workflowId: String,
        val attemptId: String,
        val expectedRevision: Long,
        val reason: String? = null,
        val idempotencyKey: String? = null,
    )

    override val name = "FACTORY__interrupt_attempt"
    override val description =
        "Explicitly interrupt a durable agent attempt of a governed workflow. Revision-fenced and subject to " +
            "Factory permissions — the agent never cancels anything outside the authoritative attempt reference."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"attemptId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"reason":{"type":"string","maxLength":500},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","attemptId","expectedRevision"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return fail("INVALID_REQUEST", "workflowId, attemptId and expectedRevision are required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return fail("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return fail("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return fail("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        // Exact allowlist of the cancel endpoint — unknown keys are rejected with
        // INVALID_REQUEST; attemptId travels in the path, never in the body.
        val payload =
            linkedMapOf<String, Any?>(
                "namespaceId" to context.namespaceId.toString(),
                "expectedRevision" to input.expectedRevision,
            )
        input.reason?.takeIf { it.isNotBlank() }?.let { payload["reason"] = it }
        val body = objectMapper.writeValueAsString(payload)
        val workflowId = encode(input.workflowId)
        val attemptId = encode(input.attemptId)
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/attempts/$attemptId/cancel")
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
                            root.path("error").path("message").asText("Factory rejected the attempt interruption."),
                        )
                    }
                    val data = root.path("data")
                    val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
                    val status = data.path("status").takeIf { it.isTextual }?.asText()
                    if (revision == null || status == null) {
                        return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                    }
                    val allowedActions = fetchAllowedActions(input.workflowId, context)
                    val output =
                        linkedMapOf<String, Any?>(
                            "status" to "accepted",
                            "revision" to revision,
                            "reasonCode" to status,
                            "interactionId" to null,
                            "proposalId" to null,
                            "allowedActions" to allowedActions,
                            "message" to "Attempt ${input.attemptId} moved to $status.",
                            "workflowId" to data.path("workflowId").asText(input.workflowId),
                            "attemptId" to data.path("attemptId").asText(input.attemptId),
                            "stepId" to data.path("stepId").asText(""),
                            "idempotent" to data.path("idempotent").asBoolean(false),
                            "reconciledVerdict" to data.path("reconciledVerdict").takeIf { it.isTextual }?.asText(),
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
