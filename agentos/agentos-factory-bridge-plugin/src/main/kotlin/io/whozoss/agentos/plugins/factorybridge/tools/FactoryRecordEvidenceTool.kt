package io.whozoss.agentos.plugins.factorybridge.tools

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

abstract class FactoryRecordEvidenceTool<T : Any>(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val mapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<T> {
    protected abstract val evidenceKind: String

    override val version = "1.0.0"

    override suspend fun execute(
        input: T?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return failure("INVALID_EVIDENCE", "Evidence is required.")
        val node = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(input)
        val workflowId = node.path("workflowId").asText("")
        val cases = context.caseEvents.map { it.caseId }.distinct()
        if (cases.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return failure("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return failure("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        node.put("kind", evidenceKind)
        val execution =
            mapOf(
                "namespaceId" to context.namespaceId.toString(),
                "runtimeId" to runtimeId,
                "kind" to "agentos",
                "agentId" to agent,
                "caseId" to cases.single().toString(),
                "actorId" to actor,
            )
        val body = mapper.writeValueAsString(mapOf("evidence" to node, "execution" to execution))
        val id = URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/evidence")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response -> parse(response.code, response.body?.string()) }
            }
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    private fun parse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        val root =
            try {
                mapper.readTree(body)
            } catch (_: Exception) {
                return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
            }
        if (status !in 200..299) {
            return failure(
                root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                root.path("error").path("message").asText("Factory rejected evidence."),
            )
        }
        val data = root.path("data")
        if (!data.path("created").isBoolean || !data.path("idempotent").isBoolean || !data.path("evidence").isObject) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        return ToolExecutionResult.success(mapper.writeValueAsString(data))
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}

class FactoryRecordAgentResultTool(
    baseUrl: String,
    http: OkHttpClient,
    mapper: ObjectMapper,
    runtimeId: String,
) : FactoryRecordEvidenceTool<FactoryRecordAgentResultTool.Input>(baseUrl, http, mapper, runtimeId) {
    data class Facts(
        val resultCode: String? = null,
        val category: String? = null,
        val attempt: Long? = null,
        val durationMs: Long? = null,
        val itemCount: Long? = null,
    )

    data class Input(
        val workflowId: String,
        val stepId: String,
        val facts: Facts,
        val outcome: String? = null,
        val idempotencyKey: String? = null,
    )

    override val evidenceKind = "agent-result"
    override val name = "FACTORY__record_agent_result"
    override val description = "Record bounded structured agent result evidence without changing workflow state."
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string"},"stepId":{"type":"string"},"outcome":{"enum":["pass","fail","indeterminate"]},"facts":{"type":"object","additionalProperties":false,"properties":{"resultCode":{"type":"string"},"category":{"type":"string"},"attempt":{"type":"integer"},"durationMs":{"type":"integer"},"itemCount":{"type":"integer"}}},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","stepId","facts"]}"""
}

class FactoryRecordArtifactTool(
    baseUrl: String,
    http: OkHttpClient,
    mapper: ObjectMapper,
    runtimeId: String,
) : FactoryRecordEvidenceTool<FactoryRecordArtifactTool.Input>(baseUrl, http, mapper, runtimeId) {
    data class Input(
        val workflowId: String,
        val stepId: String,
        val artifactRef: String,
        val artifactHash: String,
        val idempotencyKey: String? = null,
    )

    override val evidenceKind = "artifact"
    override val name = "FACTORY__record_artifact"
    override val description = "Record a hashed opaque artifact reference without reading it or changing workflow state."
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string"},"stepId":{"type":"string"},"artifactRef":{"type":"string","maxLength":1024},"artifactHash":{"type":"string","pattern":"^sha256:[0-9a-f]{64}$"},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","stepId","artifactRef","artifactHash"]}"""
}
