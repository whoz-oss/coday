package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Renews an expired result capability through the Factory's authenticated service channel.
 * The current bearer is never sent: Factory authorizes renewal from its durable attempt/case
 * correlation and returns a replacement only while that attempt remains active and unconsumed.
 */
class FactoryStepResultCapabilityRefresher(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val signer: FactoryTrustedHeaderSigner,
) {
    suspend fun refresh(
        binding: FactoryStepResultBinding,
        context: io.whozoss.agentos.sdk.tool.ToolContext,
    ): Result<FactoryStepResultBinding> =
        try {
            val body = objectMapper.writeValueAsString(
                mapOf(
                    "attemptId" to binding.attemptId,
                    "runtimeId" to binding.runtimeId,
                    "agentName" to binding.agentName,
                ),
            )
            val builder =
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/factory/agent-step-results/capability/refresh")
                    .post(body.toRequestBody("application/json".toMediaType()))
            val signed = signer.sign(builder, context).getOrElse { return Result.failure(it) }
            withContext(Dispatchers.IO) {
                httpClient.newCall(signed.build()).execute().use { response ->
                    val responseBody = response.body?.string()
                    val root = runCatching { objectMapper.readTree(responseBody) }.getOrNull()
                    val code = root?.path("error")?.path("code")?.asText("FACTORY_REQUEST_FAILED") ?: "FACTORY_REQUEST_FAILED"
                    logger.info {
                        "Factory result capability refresh: HTTP ${response.code} code=$code caseId=${binding.caseId} attemptId=${binding.attemptId}"
                    }
                    if (!response.isSuccessful) {
                        Result.failure(FactoryCapabilityRefreshException(code, response.code))
                    } else {
                        val data = root?.path("data")
                        val attemptId = data?.path("attemptId")?.asText()?.takeIf { it.isNotBlank() }
                        val token = data?.path("capabilityToken")?.asText()?.takeIf { it.length in 32..256 }
                        val expiresAt = data?.path("expiresAt")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (attemptId != binding.attemptId || token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                            Result.failure(FactoryCapabilityRefreshException("FACTORY_REFRESH_RESPONSE_INVALID", response.code))
                        } else {
                            Result.success(binding.copy(capabilityToken = token, expiresAt = expiresAt))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Result.failure(error)
        }

    companion object : KLogging()
}

class FactoryCapabilityRefreshException(
    val code: String,
    val httpStatus: Int,
) : RuntimeException("Factory capability refresh failed: $code (HTTP $httpStatus)")
