package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryCapabilityRefreshException
import io.whozoss.agentos.plugins.factorybridge.FactoryStepResultBinding
import io.whozoss.agentos.plugins.factorybridge.FactoryStepResultBindingRegistry
import io.whozoss.agentos.plugins.factorybridge.FactoryStepResultCapabilityRefresher
import mu.KLogging
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Case-scoped worker result channel. Identity and bearer capability are never model inputs. */
class FactorySubmitStepResultTool(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val mapper: ObjectMapper,
    private val bindings: FactoryStepResultBindingRegistry,
    private val refresher: FactoryStepResultCapabilityRefresher? = null,
) : StandardTool<FactorySubmitStepResultTool.Input> {
    data class Artifact(val kind: String, val encoding: String = "markdown", val content: String)

    data class Claims(val modifiedFiles: List<String>)

    data class Finding(
        val severity: String,
        val code: String,
        val summary: String,
        val file: String? = null,
        val line: Int? = null,
    )

    data class Input(
        val status: String,
        val summary: String,
        val artifacts: List<Artifact> = emptyList(),
        val claims: Claims,
        val findings: List<Finding> = emptyList(),
    )

    override val name = "FACTORY_WORKER__submit_step_result"
    override val description = "Submit the authoritative structured result for this Factory step attempt. Attempt identity is injected by the runtime."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"status":{"enum":["PASS","FAIL"]},"summary":{"type":"string","minLength":1,"maxLength":2000},"artifacts":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"kind":{"type":"string","maxLength":128},"encoding":{"const":"markdown"},"content":{"type":"string","minLength":1,"maxLength":262144}},"required":["kind","encoding","content"]}},"claims":{"type":"object","additionalProperties":false,"properties":{"modifiedFiles":{"type":"array","maxItems":1000,"items":{"type":"string","maxLength":1024}}},"required":["modifiedFiles"]},"findings":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"properties":{"severity":{"enum":["info","warning","error","blocking"]},"code":{"type":"string","maxLength":128},"summary":{"type":"string","maxLength":1000},"file":{"type":"string","maxLength":1024},"line":{"type":"integer","minimum":1}},"required":["severity","code","summary"]}}},"required":["status","summary","claims"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return failure("RESULT_SCHEMA_INVALID", "A structured result is required.")
        val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
            ?: return failure("FACTORY_WORKER_BINDING_INVALID", "Factory worker invocation requires exactly one controlling case.")
        val agent = context.agentName
            ?: return failure("FACTORY_WORKER_BINDING_INVALID", "Factory worker invocation requires an agent identity.")
        var binding = bindings.acquire(caseId, context.namespaceId, agent, allowExpired = refresher != null)
            ?: return failure(
                "FACTORY_WORKER_BINDING_MISSING",
                "No active Factory attempt binding matches this case, namespace, and agent, or the binding is unavailable.",
            )
        if (!binding.expiresAt.isAfter(java.time.Instant.now())) {
            binding = refresh(binding, context).getOrElse { error ->
                bindings.release(binding)
                val code = (error as? FactoryCapabilityRefreshException)?.code ?: "FACTORY_CAPABILITY_REFRESH_FAILED"
                return failure(code, "Factory result capability could not be renewed: $code.")
            }
        }
        val resultPayload = linkedMapOf<String, Any?>(
            "status" to input.status,
            "summary" to input.summary,
            "artifacts" to input.artifacts,
            "claims" to input.claims,
            "findings" to input.findings.map { finding ->
                linkedMapOf<String, Any?>(
                    "severity" to finding.severity,
                    "code" to finding.code,
                    "summary" to finding.summary,
                ).apply {
                    finding.file?.let { put("file", it) }
                    finding.line?.let { put("line", it) }
                }
            },
        )
        val body = mapper.writeValueAsString(mapOf("attemptId" to binding.attemptId, "result" to resultPayload))
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/agent-step-results")
                .header("Authorization", "Bearer ${binding.capabilityToken}")
                .header("X-AgentOS-Case-Id", caseId.toString())
                .header("X-AgentOS-Agent-Name", agent)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    val root = runCatching { mapper.readTree(r.body?.string()) }.getOrNull()
                    val code = root?.path("error")?.path("code")?.asText("FACTORY_REQUEST_FAILED") ?: "FACTORY_REQUEST_FAILED"
                    logger.info {
                        "Factory result submission: HTTP ${r.code} code=$code caseId=$caseId attemptId=${binding.attemptId} agent=$agent"
                    }
                    when {
                        r.isSuccessful -> {
                            bindings.acknowledge(binding)
                            ToolExecutionResult.success(mapper.writeValueAsString(root?.path("data")))
                        }

                        r.code == 410 && code == "RESULT_CAPABILITY_EXPIRED" && refresher != null -> {
                            val refreshed = refresh(binding, context).getOrElse { error ->
                                bindings.release(binding)
                                val refreshCode = (error as? FactoryCapabilityRefreshException)?.code ?: "FACTORY_CAPABILITY_REFRESH_FAILED"
                                return@use failure(refreshCode, "Factory result capability could not be renewed: $refreshCode.")
                            }
                            binding = refreshed
                            submitOnce(caseId, agent, binding, body)
                        }

                        r.code >= 500 || code == "RESULT_SCHEMA_INVALID" -> {
                            bindings.release(binding)
                            failure(code, "Factory rejected the result: $code (HTTP ${r.code}). Correct the structured arguments and retry.")
                        }

                        else -> {
                            bindings.invalidate(binding)
                            failure(code, "Factory rejected the result: $code (HTTP ${r.code}).")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            bindings.release(binding)
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable; the result may be retried.")
        }
    }

    private suspend fun refresh(
        binding: FactoryStepResultBinding,
        context: ToolContext,
    ): Result<FactoryStepResultBinding> {
        val refreshed = refresher?.refresh(binding, context)
            ?: return Result.failure(IllegalStateException("Capability refresh is unavailable"))
        return refreshed.mapCatching { replacement ->
            check(bindings.replaceLeased(binding, replacement)) { "Factory result binding changed while capability was renewed" }
            replacement
        }
    }

    private suspend fun submitOnce(
        caseId: java.util.UUID,
        agent: String,
        binding: FactoryStepResultBinding,
        body: String,
    ): ToolExecutionResult =
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/factory/agent-step-results")
                    .header("Authorization", "Bearer ${binding.capabilityToken}")
                    .header("X-AgentOS-Case-Id", caseId.toString())
                    .header("X-AgentOS-Agent-Name", agent)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    val root = runCatching { mapper.readTree(response.body?.string()) }.getOrNull()
                    val code = root?.path("error")?.path("code")?.asText("FACTORY_REQUEST_FAILED") ?: "FACTORY_REQUEST_FAILED"
                    logger.info {
                        "Factory result resubmission: HTTP ${response.code} code=$code caseId=$caseId attemptId=${binding.attemptId} agent=$agent"
                    }
                    when {
                        response.isSuccessful -> {
                            bindings.acknowledge(binding)
                            ToolExecutionResult.success(mapper.writeValueAsString(root?.path("data")))
                        }
                        response.code >= 500 || code == "RESULT_SCHEMA_INVALID" -> {
                            bindings.release(binding)
                            failure(code, "Factory rejected the result: $code (HTTP ${response.code}). Correct the structured arguments and retry.")
                        }
                        else -> {
                            bindings.invalidate(binding)
                            failure(code, "Factory rejected the result after one controlled renewal: $code (HTTP ${response.code}).")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            bindings.release(binding)
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable; the result may be retried.")
        }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    companion object : KLogging()
}
