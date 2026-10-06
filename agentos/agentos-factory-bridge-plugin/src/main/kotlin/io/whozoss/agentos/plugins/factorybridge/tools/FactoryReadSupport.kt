package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * Shared helpers of the Phase 6 read-only Workstream Agent tools.
 *
 * Every read tool is config-less, injects `namespaceId` from the trusted
 * `ToolContext` (never from model input) and maps transport/Factory failures
 * onto the stable error codes `FACTORY_TIMEOUT`, `FACTORY_UNAVAILABLE`,
 * `MALFORMED_FACTORY_RESPONSE` and `FACTORY_REQUEST_FAILED` — the latter
 * carrying the Factory `error.code`/`error.message` verbatim when the envelope
 * provides them (e.g. a 403 `WORKSTREAM_BOUNDARY_VIOLATION`).
 */
internal object FactoryReadSupport {
    /** Bounded workflow identifier (contract §6.1). */
    val WORKFLOW_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** Bounded generic identifier (step/workstream slugs; `:` and `@` allowed). */
    val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")

    /** URL-encode one path segment. */
    fun encodePathSegment(id: String): String = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    /** URL-encode one query parameter value. */
    fun encodeQueryValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    fun failure(
        code: String,
        message: String,
    ): ToolExecutionResult = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    /**
     * Run a GET on [Dispatchers.IO] and delegate the response to [onResponse].
     * Transport failures are mapped to the stable codes.
     */
    suspend fun executeGet(
        httpClient: OkHttpClient,
        request: Request,
        onResponse: (status: Int, body: String?) -> ToolExecutionResult,
    ): ToolExecutionResult =
        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { onResponse(it.code, it.body?.string()) }
            }
        } catch (_: SocketTimeoutException) {
            failure("FACTORY_TIMEOUT", "Factory did not respond before the timeout.")
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }

    /** Parse a response body, or `null` when it is not valid JSON. */
    fun parseJson(
        objectMapper: ObjectMapper,
        body: String?,
    ): JsonNode? =
        try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            null
        }

    /**
     * Map a non-2xx Factory response to a failure carrying the Factory
     * `error.code`/`error.message` when present (`FACTORY_REQUEST_FAILED`
     * otherwise).
     */
    fun errorResult(
        objectMapper: ObjectMapper,
        body: String?,
        fallbackMessage: String,
    ): ToolExecutionResult {
        val root = parseJson(objectMapper, body)
        val code = root?.path("error")?.path("code")?.takeIf { it.isTextual }?.asText() ?: "FACTORY_REQUEST_FAILED"
        val message = root?.path("error")?.path("message")?.takeIf { it.isTextual }?.asText() ?: fallbackMessage
        return failure(code, message)
    }
}
