package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import java.net.URLEncoder

class FactoryStartWorkflowTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val trustedHeaderSigner: FactoryTrustedHeaderSigner,
) : StandardTool<FactoryStartWorkflowTool.Input> {
    data class Input(val workflowType: String, val title: String? = null, val ticket: String? = null)

    override val name = "FACTORY_WORKSTREAM__start_workflow"
    override val description = "Create an authoritative governed workflow from the unique configured immutable definition."
    override val version = "2.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowType":{"type":"string","maxLength":128},"title":{"type":"string","maxLength":200},"ticket":{"type":"string","maxLength":64}},"required":["workflowType"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.workflowType.isBlank()) return failure("INVALID_START_REQUEST", "workflowType is required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val requestIdentity = context.toolRequestId?.takeIf { it.isNotBlank() }
            ?: return failure("TOOL_REQUEST_CONTEXT_UNAVAILABLE", "A stable tool request identity is required.")
        val parameters = buildMap<String, Any> { input.ticket?.trim()?.takeIf { it.isNotEmpty() }?.let { put("ticket", it) } }
        val body = objectMapper.writeValueAsString(buildMap<String, Any> {
            put("workflowType", input.workflowType)
            input.title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            if (parameters.isNotEmpty()) put("parameters", parameters)
        })
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/factory/workflows")
            .header("Idempotency-Key", "agentos-tool:$requestIdentity")
            .post(body.toRequestBody("application/json".toMediaType()))
        val request = trustedHeaderSigner.sign(requestBuilder, context).getOrElse {
            return failure("TRUST_CONTEXT_UNAVAILABLE", it.message ?: "Trusted Factory context is unavailable.")
        }.build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val result = parseResponse(response.code, response.body?.string())
                    if (!result.success) return@use result
                    // Best-effort allowedActions enrichment (Phase 7 standardized
                    // command output): a failed actions read never fails the command.
                    val workflowId = result.metadata["workflowId"] as? String ?: return@use result
                    val allowedActions = fetchAllowedActions(workflowId, context)
                    val output = objectMapper.readTree(result.output) as ObjectNode
                    output.set<JsonNode>("allowedActions", allowedActions)
                    ToolExecutionResult.success(
                        objectMapper.writeValueAsString(output),
                        metadata = result.metadata + ("allowedActions" to allowedActions),
                    )
                }
            }
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    /** Best-effort enrichment: a failed actions read never fails the command. */
    private fun fetchAllowedActions(
        workflowId: String,
        context: ToolContext,
    ): JsonNode =
        try {
            val id = URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
            val ns = URLEncoder.encode(context.namespaceId.toString(), Charsets.UTF_8).replace("+", "%20")
            httpClient
                .newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/actions?namespaceId=$ns").get().build())
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

    internal fun parseResponse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        val root =
            try {
                objectMapper.readTree(body)
            } catch (_: Exception) {
                return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
            }
        if (status !in 200..299) {
            return failure(
                root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                root.path("error").path("message").asText("Factory rejected workflow creation."),
            )
        }
        val data = root.path("data")
        val workflowId = data.path("workflowId").takeIf { it.isTextual }?.asText()
        val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
        val title = data.path("title").takeIf { it.isTextual }?.asText()
        val created = data.path("created").takeIf { it.isBoolean }?.asBoolean()
        val queued = data.path("queued").takeIf { it.isBoolean }?.asBoolean()
        val idempotent = data.path("idempotent").takeIf { it.isBoolean }?.asBoolean()
        val submissionId = data.path("submissionId").takeIf { it.isTextual }?.asText()
        val submissionStatus = data.path("submissionStatus").takeIf { it.isTextual }?.asText()
        if (workflowId == null || title == null || revision == null || created == null || queued == null || idempotent == null || submissionId == null || submissionStatus == null) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        // Phase 7 standardized command output: status / revision / reasonCode /
        // interactionId|proposalId / allowedActions (enriched in execute) / message,
        // while preserving the start-specific rich fields.
        val output =
            linkedMapOf<String, Any?>(
                "status" to "accepted",
                "revision" to revision,
                "reasonCode" to null,
                "interactionId" to null,
                "proposalId" to null,
                "allowedActions" to emptyList<Any>(),
                "message" to if (created) "Workflow $workflowId created." else "Workflow $workflowId already exists (idempotent).",
                "workflowId" to workflowId,
                "title" to title,
                "created" to created,
                "queued" to queued,
                "idempotent" to idempotent,
                "submissionId" to submissionId,
                "submissionStatus" to submissionStatus,
                "governanceMode" to data.path("governanceMode").asText(),
                "definitionVersion" to data.path("definitionVersion").asText(),
                "definitionHash" to data.path("definitionHash").asText(),
                "projection" to data.path("projection"),
            )
        return ToolExecutionResult.success(objectMapper.writeValueAsString(output), metadata = output.filterKeys { it != "projection" })
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
