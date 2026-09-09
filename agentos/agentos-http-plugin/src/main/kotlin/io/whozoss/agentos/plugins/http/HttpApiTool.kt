package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.net.executeCancellable
import io.whozoss.agentos.plugins.http.openapi.HttpMethod
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor
import io.whozoss.agentos.plugins.http.openapi.ToolNaming
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * One curated OpenAPI operation exposed as an agent tool.
 *
 * The input schema is the one built for the operation, so the LLM arguments arrive as raw JSON
 * ([Input.args], filled by [executeWithJson]) and are parsed inside the plugin classloader. A call never
 * retries and never follows a redirect, and cancelling the run cancels the call. Non-GET operations ask
 * for confirmation on every call ([ConfirmationMode.EVERY_TIME]), which only the advanced agent honours.
 */
class HttpApiTool(
    private val descriptor: OperationDescriptor,
    private val runtime: HttpApiRuntime,
) : StandardTool<HttpApiTool.Input> {

    /** Raw JSON arguments of the LLM, deserialised in [execute] against the operation's own schema. */
    data class Input(val args: String?)

    override val name: String = ToolNaming.toolName(configName = runtime.configName, suffix = descriptor.toolSuffix)

    override val description: String = descriptor.description

    override val inputSchema: String = descriptor.inputSchema

    override val version: String = VERSION

    override val paramType: Class<Input> = Input::class.java

    override suspend fun executeWithJson(json: String?, context: ToolContext): ToolExecutionResult =
        execute(Input(args = json), context)

    override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?): ConfirmationMode =
        if (descriptor.method == HttpMethod.GET) ConfirmationMode.NONE else ConfirmationMode.EVERY_TIME

    override fun getConfirmationInstructions(): String =
        if (descriptor.method == HttpMethod.GET) {
            ""
        } else {
            "This tool performs a write on an external system through HTTP_API integration " +
                "'${runtime.configName}'; mutations are never implicit and require explicit user consent."
        }

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        val args = parseArguments(input?.args)
            ?: return failure(
                output = "arguments must be a JSON object matching the tool schema",
                errorType = HttpApiErrors.INVALID_INPUT,
            )
        val auth = runtime.authSpec
        if (auth is AuthHeaderSpec.Missing) {
            return failure(
                output = "Authentication is not available for HTTP_API integration '${runtime.configName}': " +
                    "${auth.reason}. Ask an administrator to check the bound Auth Setting; do not retry.",
                errorType = HttpApiErrors.AUTH_MISSING,
            )
        }
        val request = when (val plan = HttpRequestFactory.build(descriptor, runtime, args)) {
            is RequestPlan.Ready -> plan.request
            is RequestPlan.Rejected -> return failure(output = plan.message, errorType = plan.errorType)
        }
        return send(request)
    }

    /** Null or blank arguments mean no arguments; anything that is not a JSON object is refused. */
    private fun parseArguments(json: String?): JsonNode? {
        if (json.isNullOrBlank()) return JsonNodeFactory.instance.objectNode()
        return try {
            HttpApiJson.mapper.readTree(json).takeIf { it.isObject }
        } catch (e: JsonProcessingException) {
            null
        }
    }

    /**
     * Waits for a permit of the config's semaphore for at most the call timeout, then performs the call.
     * The flag, not the value of [withTimeoutOrNull], tells whether the permit was obtained: the timeout
     * can fire right after `acquire()` returned, in which case the block answers null with the permit
     * held; the flag makes sure such a permit is used and released rather than leaked.
     */
    private suspend fun send(request: Request): ToolExecutionResult {
        val path = request.url.encodedPath
        var acquired = false
        try {
            withTimeoutOrNull(runtime.timeoutSeconds.seconds) {
                runtime.semaphore.acquire()
                acquired = true
            }
            if (!acquired) {
                return failure(
                    output = "too many concurrent calls to HTTP_API integration '${runtime.configName}': " +
                        "try again later",
                    errorType = HttpApiErrors.TRANSPORT_ERROR,
                    path = path,
                )
            }
            return perform(request, path)
        } finally {
            if (acquired) runtime.semaphore.release()
        }
    }

    private suspend fun perform(request: Request, path: String): ToolExecutionResult {
        val started = System.nanoTime()
        return try {
            val result = runtime.client.newCall(request).executeCancellable { response ->
                HttpResponseMapper.map(response, descriptor, configName = runtime.configName, path = path)
            }
            val status = "${result.metadata["status"]}"
            val detail = "${result.metadata["bytes"]} bytes"
            logCall(request, outcome = status, detail = detail, startedNanos = started, success = result.success)
            result
        } catch (e: IOException) {
            val kind = e::class.simpleName ?: "IOException"
            val message = e.message?.substringBefore('?').orEmpty()
            logCall(request, outcome = kind, detail = message, startedNanos = started, success = false)
            failure(
                output = "transport error calling ${request.method} $path: $kind: $message",
                errorType = HttpApiErrors.TRANSPORT_ERROR,
                path = path,
            )
        }
    }

    /** One line per call, never a header, a query string nor a body. */
    private fun logCall(request: Request, outcome: String, detail: String, startedNanos: Long, success: Boolean) {
        val millis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val url = request.url
        val line = "HTTP_API '${runtime.configName}': ${request.method} ${url.host} ${url.encodedPath}" +
            " -> $outcome in ${millis}ms ($detail)"
        if (success) logger.info { line } else logger.warn { line }
    }

    private fun failure(output: String, errorType: String, path: String? = null): ToolExecutionResult =
        ToolExecutionResult(
            output = output,
            success = false,
            metadata = mapOf(
                "status" to null,
                "contentType" to null,
                "bytes" to 0,
                "truncated" to false,
                "path" to path,
            ),
            errorType = errorType,
            errorMessage = output,
        )

    companion object : KLogging() {
        const val VERSION = "1"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
