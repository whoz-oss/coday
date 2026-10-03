package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryProjectionValidation
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

class FactoryPublishProjectionTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String = "agentos-primary",
) : StandardTool<FactoryPublishProjectionTool.Input> {
    data class Responsibility(val kind: String, val name: String? = null)

    data class Step(
        val id: String,
        val name: String,
        val status: String,
        val description: String? = null,
        val dependsOn: List<String> = emptyList(),
        val responsibility: Responsibility? = null,
    )

    data class Input(
        val schemaVersion: String,
        val workflowId: String,
        val workflowType: String,
        val title: String,
        val status: String,
        val expectedRevision: Long? = null,
        val steps: List<Step>,
    )

    override val name = "FACTORY__publish_projection"
    override val description = "Publish a deterministic generic WorkflowProjection v1 or v2 to Factory. Execution identity is derived from AgentOS context."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"schemaVersion":{"enum":["1","2"]},"workflowId":{"type":"string"},"workflowType":{"type":"string"},"title":{"type":"string"},"status":{"enum":["pending","ready","running","waiting_human","blocked","completed","failed","cancelled"]},"expectedRevision":{"type":"integer","minimum":0},"steps":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string"},"name":{"type":"string"},"status":{"enum":["pending","ready","running","waiting_human","blocked","completed","failed","cancelled"]},"description":{"type":"string"},"dependsOn":{"type":"array","items":{"type":"string"}},"responsibility":{"type":"object","additionalProperties":false,"properties":{"kind":{"enum":["human","agent","code"]},"name":{"type":"string","maxLength":256}},"required":["kind"]}},"required":["id","name","status"]}}},"required":["schemaVersion","workflowId","workflowType","title","status","steps"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        FactoryProjectionValidation.validate(input)?.let {
            return failure(it.code, "${it.field}: ${it.reason}")
        }
        input!!
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A consistent case identity is required.")
        val agentName =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return failure("AGENT_CONTEXT_UNAVAILABLE", "Agent identity is required.")
        val userIdentity =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return failure("USER_CONTEXT_UNAVAILABLE", "User identity is required.")
        val execution =
            mapOf(
                "namespaceId" to context.namespaceId.toString(),
                "runtimeId" to runtimeId,
                "kind" to "agentos",
                "actorId" to userIdentity,
                "agentId" to agentName,
                "caseId" to caseIds.single().toString(),
            )
        val body = objectMapper.writeValueAsString(mapOf("projection" to projectionPayload(input), "execution" to execution))
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/${java.net.URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")}/projection")
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response -> parseResponse(response.code, response.body?.string()) }
            }
        } catch (_: SocketTimeoutException) {
            failure("FACTORY_TIMEOUT", "Factory did not respond before the timeout.")
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    internal fun projectionPayload(input: Input): Map<String, Any> =
        buildMap {
            put("schemaVersion", input.schemaVersion)
            put("workflowId", input.workflowId)
            put("workflowType", input.workflowType)
            put("title", input.title)
            put("status", input.status)
            input.expectedRevision?.let { put("expectedRevision", it) }
            put(
                "steps",
                input.steps.map { step ->
                    buildMap<String, Any> {
                        put("id", step.id)
                        put("name", step.name)
                        put("status", step.status)
                        step.description?.let { put("description", it) }
                        put("dependsOn", step.dependsOn)
                        step.responsibility?.let { actor ->
                            put(
                                "responsibility",
                                buildMap<String, Any> {
                                    put("kind", actor.kind)
                                    actor.name?.let { put("name", it) }
                                },
                            )
                        }
                    }
                },
            )
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
        if (status in 200..299) {
            val data = root.path("data")
            val workflowId = data.path("workflowId").takeIf { it.isTextual }?.asText()
            val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
            val changed = data.path("changed").takeIf { it.isBoolean }?.asBoolean()
            if (workflowId == null || revision == null || changed == null) {
                return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
            }
            val updatedAt = java.time.Instant.now().toString()
            val output = objectMapper.writeValueAsString(mapOf("workflowId" to workflowId, "revision" to revision, "changed" to changed, "updatedAt" to updatedAt))
            return ToolExecutionResult.success(output, metadata = mapOf("workflowId" to workflowId, "revision" to revision, "changed" to changed, "updatedAt" to updatedAt))
        }
        val code = root.path("error").path("code").takeIf { it.isTextual }?.asText() ?: "FACTORY_REQUEST_FAILED"
        val message = root.path("error").path("message").takeIf { it.isTextual }?.asText() ?: "Factory rejected the publication."
        return failure(code, message)
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
