package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryTrustedHeaderSigner
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class FactorySubmitStepResultTool(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val mapper: ObjectMapper,
    private val signer: FactoryTrustedHeaderSigner,
    // Supplied by AgentOS when resolving tools for this agent, never by model input.
    private val resolvedAgentName: String? = null,
) : StandardTool<FactorySubmitStepResultTool.Input> {
    data class Artifact(val kind: String, val encoding: String = "markdown", val content: String)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class DeliveryAnalysis(val changeType: String, val ticket: String, val title: String, val baseBranch: String? = null)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Claims(val modifiedFiles: List<String>, val deliveryAnalysis: DeliveryAnalysis? = null)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Finding(val severity: String, val code: String, val summary: String, val file: String? = null, val line: Int? = null)
    data class Input(
        val status: String,
        val summary: String,
        val artifacts: List<Artifact> = emptyList(),
        val claims: Claims,
        val findings: List<Finding> = emptyList(),
    )

    override val name = "FACTORY_WORKER__submit_step_result"
    override val description = "Submit this case's structured result to Factory. Factory resolves the current attempt from trusted case identity."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema = SCHEMA

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) return error("RESULT_SCHEMA_INVALID", "A structured result is required")
        val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
            ?: return error("FACTORY_CASE_CONTEXT_INVALID", "Exactly one controlling case is required")
        val executionAgentName = context.agentName?.takeIf(String::isNotBlank)
        if (executionAgentName != null && resolvedAgentName != null && executionAgentName != resolvedAgentName) {
            return error("FACTORY_AGENT_CONTEXT_INVALID", "Execution agent does not match the agent owning this tool")
        }
        val agentName = executionAgentName ?: resolvedAgentName
            ?: return error("FACTORY_AGENT_CONTEXT_INVALID", "A trusted agent identity is required")
        val idempotencyKey = stableIdempotencyKey(caseId, context.toolRequestId)
            ?: return error("FACTORY_IDEMPOTENCY_CONTEXT_MISSING", "A trusted tool request id is required")
        val payload = mapper.writeValueAsString(mapOf("result" to input))
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/factory/agent-step-results")
            .header("X-AgentOS-Agent-Name", agentName)
            .header("Idempotency-Key", idempotencyKey)
            .post(payload.toRequestBody(JSON))
        val request = signer.sign(requestBuilder, context).getOrElse {
            return error("TRUST_CONTEXT_UNAVAILABLE", it.message ?: "Trusted Factory context is unavailable")
        }.build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val root = runCatching { mapper.readTree(body) }.getOrNull()
                    val code = root?.path("error")?.path("code")?.asText()?.takeIf(String::isNotBlank)
                        ?: "FACTORY_REQUEST_REJECTED"
                    if (response.isSuccessful) ToolExecutionResult.success(body)
                    else error(code, "Factory rejected the result (HTTP ${response.code}, code=$code)")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            error("FACTORY_UNAVAILABLE", "Factory is unavailable; retrying this same tool request is idempotent")
        }
    }

    private fun stableIdempotencyKey(caseId: UUID, requestId: String?): String? =
        requestId?.takeIf(String::isNotBlank)?.let { "agentos:$caseId:$it" }

    private fun error(code: String, message: String) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    companion object {
        private val JSON = "application/json".toMediaType()
        private val SCHEMA = """{"type":"object","additionalProperties":false,"properties":{"status":{"enum":["PASS","FAIL","NEEDS_RESEARCH","CHANGES_REQUESTED"]},"summary":{"type":"string","minLength":1,"maxLength":2000},"artifacts":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"kind":{"type":"string","maxLength":128},"encoding":{"const":"markdown"},"content":{"type":"string","minLength":1,"maxLength":262144}},"required":["kind","encoding","content"]}},"claims":{"type":"object","additionalProperties":false,"properties":{"modifiedFiles":{"type":"array","maxItems":1000,"items":{"type":"string","maxLength":1024}},"deliveryAnalysis":{"type":"object","additionalProperties":false,"properties":{"changeType":{"enum":["feature","bugfix"]},"ticket":{"type":"string","pattern":"^WZ-[0-9]+$"},"title":{"type":"string","minLength":1,"maxLength":200},"baseBranch":{"type":"string","minLength":1,"maxLength":255}},"required":["changeType","ticket","title"]}},"required":["modifiedFiles"]},"findings":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"properties":{"severity":{"enum":["info","warning","error","blocking"]},"code":{"type":"string","maxLength":128},"summary":{"type":"string","maxLength":1000},"file":{"type":"string","maxLength":1024},"line":{"type":"integer","minimum":1}},"required":["severity","code","summary"]}}},"required":["status","summary","claims"]}"""
    }
}
