package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

@Deprecated(
    "Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md",
    level = DeprecationLevel.WARNING,
)
class FactoryStartWorkflowTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryStartWorkflowTool.Input> {
    data class Input(val workflowId: String, val workflowType: String, val title: String)

    override val name = "FACTORY__start_workflow"
    override val description = "Create an authoritative governed workflow from the unique configured immutable definition."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string"},"workflowType":{"type":"string"},"title":{"type":"string"}},"required":["workflowId","workflowType","title"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return failure("INVALID_START_REQUEST", "workflowId, workflowType and title are required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return failure("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val execution =
            linkedMapOf<String, Any>(
                "namespaceId" to context.namespaceId.toString(),
                "runtimeId" to runtimeId,
                "kind" to "agentos",
                "agentId" to agent,
                "caseId" to caseIds.single().toString(),
            )
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return failure("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        execution["actorId"] = actor
        val body = objectMapper.writeValueAsString(mapOf("workflow" to input, "execution" to execution))
        val id = URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/start")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
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
        val created = data.path("created").takeIf { it.isBoolean }?.asBoolean()
        val idempotent = data.path("idempotent").takeIf { it.isBoolean }?.asBoolean()
        if (workflowId == null || revision == null || created == null || idempotent == null) {
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
                "created" to created,
                "idempotent" to idempotent,
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
