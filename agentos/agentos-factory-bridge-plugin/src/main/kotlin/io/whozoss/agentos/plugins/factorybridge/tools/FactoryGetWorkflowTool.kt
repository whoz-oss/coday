package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.URLEncoder

class FactoryGetWorkflowTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetWorkflowTool.Input> {
    data class Input(val workflowId: String)

    override val name = "FACTORY__get_workflow"
    override val description = "Read the authoritative namespace-scoped state of a Factory workflow before creation or resume."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"}},"required":["workflowId"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val workflowId = input?.workflowId
        if (workflowId == null || !SAFE_ID.matches(workflowId)) return failure("INVALID_WORKFLOW_ID", "workflowId is invalid.")
        val encoded = URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded?namespaceId=${context.namespaceId}")
                .get()
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { parseResponse(workflowId, it.code, it.body?.string()) }
            }
        } catch (_: SocketTimeoutException) {
            failure("FACTORY_TIMEOUT", "Factory did not respond before the timeout.")
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    internal fun parseResponse(
        workflowId: String,
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
            val code = root.path("error").path("code").takeIf { it.isTextual }?.asText() ?: "FACTORY_REQUEST_FAILED"
            val message =
                root.path("error").path("message").takeIf { it.isTextual }?.asText() ?: "Factory rejected the lookup."
            return failure(code, message)
        }
        val data = root.path("data")
        val state = data.path("state").takeIf { it.isTextual }?.asText()
        if (data.path("workflowId").asText(null) != workflowId || state !in STATES) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val output =
            if (state != "existing") {
                mapOf("state" to state, "workflowId" to workflowId)
            } else {
                val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
                val projection = data.path("projection").takeIf { it.isObject }
                val workflowType = projection?.path("workflowType")?.takeIf { it.isTextual }?.asText()
                if (revision == null || projection == null || projection.path("workflowId").asText(null) != workflowId || workflowType == null) {
                    return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                }
                mapOf(
                    "state" to state,
                    "workflowId" to workflowId,
                    "revision" to revision,
                    "workflowType" to workflowType,
                    "status" to projection.path("status").asText(),
                    "projection" to projection,
                )
            }
        return ToolExecutionResult.success(objectMapper.writeValueAsString(output), metadata = output.filterKeys { it != "projection" })
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val STATES = setOf("absent", "existing", "removed", "purged")
    }
}
