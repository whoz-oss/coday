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
import java.net.URLEncoder

@Deprecated(
    "Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md",
    level = DeprecationLevel.WARNING,
)
class FactoryRequestTransitionTool(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val mapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryRequestTransitionTool.Input> {
    data class Input(
        val workflowId: String,
        val stepId: String,
        val expectedRevision: Long,
        val requestedStatus: String,
        val evidenceIds: List<String>,
        val idempotencyKey: String? = null,
    )

    override val name = "FACTORY__request_transition"
    override val description = "Request a governed transition for an agent-owned workflow step."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"stepId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"requestedStatus":{"enum":["pending","ready","running","waiting_human","blocked","completed","failed","cancelled"]},"evidenceIds":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","maxLength":128}},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","stepId","expectedRevision","requestedStatus","evidenceIds"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return fail("INVALID_TRANSITION_REQUEST", "Transition is required.")
        val cases = context.caseEvents.map { it.caseId }.distinct()
        if (cases.size != 1) return fail("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return fail("AGENT_CONTEXT_UNAVAILABLE", "Agent identity is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return fail("USER_CONTEXT_UNAVAILABLE", "Actor identity is required.")
        val execution =
            mapOf(
                "namespaceId" to context.namespaceId.toString(),
                "runtimeId" to runtimeId,
                "kind" to "agentos",
                "agentId" to agent,
                "caseId" to cases.single().toString(),
                "actorId" to actor,
            )
        val body = mapper.writeValueAsString(mapOf("transition" to input, "execution" to execution))
        val id = URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request
                        .Builder()
                        .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/transitions")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute().use { r ->
                    val root =
                        try {
                            mapper.readTree(r.body?.string())
                        } catch (_: Exception) {
                            return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned invalid response.")
                        }
                    if (!r.isSuccessful) {
                        return@use fail(
                            root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                            root.path("error").path("message").asText("Transition rejected."),
                        )
                    }
                    val data = root.path("data")
                    if (!data.path("revision").isIntegralNumber || !data.path("changed").isBoolean) {
                        return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned invalid response.")
                    }
                    val revision = data.path("revision").asLong()
                    val changed = data.path("changed").asBoolean()
                    // Phase 7 standardized command output: the policy/control plane
                    // decided (changed or idempotent) — the agent only proposed.
                    val output =
                        linkedMapOf<String, Any?>(
                            "status" to "accepted",
                            "revision" to revision,
                            "reasonCode" to null,
                            "interactionId" to null,
                            "proposalId" to null,
                            "allowedActions" to fetchAllowedActions(input.workflowId, context),
                            "message" to
                                if (changed) {
                                    "Transition to ${input.requestedStatus} applied for step ${input.stepId}."
                                } else {
                                    "Transition already applied (idempotent)."
                                },
                            "workflowId" to data.path("workflowId").asText(input.workflowId),
                            "stepId" to input.stepId,
                            "requestedStatus" to input.requestedStatus,
                            "changed" to changed,
                        )
                    ToolExecutionResult.success(mapper.writeValueAsString(output))
                }
            }
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
            val id = URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
            val ns = URLEncoder.encode(context.namespaceId.toString(), Charsets.UTF_8).replace("+", "%20")
            http
                .newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/actions?namespaceId=$ns").get().build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use mapper.createArrayNode()
                    val root = mapper.readTree(response.body?.string())
                    root.path("data").path("allowedActions").takeIf { it.isArray }
                        ?: mapper.createArrayNode()
                }
        } catch (_: Exception) {
            mapper.createArrayNode()
        }

    private fun fail(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
