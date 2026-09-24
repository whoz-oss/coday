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

/** Factory-owned provisioning. No repository root or destination is accepted from the model. */
class FactoryProvisionEnvironmentTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryProvisionEnvironmentTool.Input> {
    data class Input(val workflowId: String, val workUnitId: String, val integrationBranch: String, val branch: String)

    override val name = "FACTORY__provision_environment"
    override val description = "Provision and bind the Factory-owned isolated worktree for this governed workflow and controlling case."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string"},"workUnitId":{"type":"string"},"integrationBranch":{"type":"string"},"branch":{"type":"string"}},"required":["workflowId","workUnitId","integrationBranch","branch"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return failure("INVALID_ENVIRONMENT_REQUEST", "Environment request is required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return failure("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        val id = URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$id/environment/provision")
                .header("X-Factory-Namespace-Id", context.namespaceId.toString())
                .header("X-Factory-Case-Id", caseIds.single().toString())
                .header("X-Factory-Actor-Id", actor)
                .post(objectMapper.writeValueAsString(input).toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { parse(it.code, it.body?.string()) } }
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    internal fun parse(
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
                root.path("error").path("code").asText("ENVIRONMENT_NOT_BOUND"),
                root.path("error").path("message").asText("Environment was not bound."),
            )
        }
        val data = root.path("data")
        if (data.path("fileAccess").path("status").asText() != "bound") {
            return failure("ENVIRONMENT_NOT_BOUND", "Factory did not prove FILE_ACCESS binding.")
        }
        val environment = data.path("environment")
        if (environment.path("workflowId").asText().isBlank() || environment.path("lifecycleState").asText() != "active") {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid environment binding.")
        }
        val safeOutput =
            mapOf(
                "environmentId" to environment.path("environmentId").asText(),
                "workflowId" to environment.path("workflowId").asText(),
                "workUnitId" to environment.path("workUnitId").asText(),
                "lifecycleState" to "active",
                "baseCommit" to environment.path("baseCommit").asText(),
                "headCommit" to data.path("headCommit").asText(),
                "fileAccessStatus" to "bound",
            )
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(safeOutput),
            metadata = mapOf("environmentId" to environment.path("environmentId").asText(), "status" to "bound"),
        )
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
