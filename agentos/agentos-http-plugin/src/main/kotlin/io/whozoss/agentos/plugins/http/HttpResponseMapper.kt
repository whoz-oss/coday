package io.whozoss.agentos.plugins.http

import io.whozoss.agentos.plugins.http.net.BoundedBodyReader
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor
import io.whozoss.agentos.plugins.http.response.ResponseShaper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.Response

/**
 * Maps an HTTP answer to a [ToolExecutionResult]: shaped output on 2xx, one stable error type per class of
 * failure otherwise. The body is read with a hard cap of [BYTE_CAP_FACTOR] times the character cap of the
 * operation; the metadata carries `status`, `contentType`, `bytes`, `truncated` and the request `path` only
 * (never the host, the query string or a header). Bodies are never logged, at any level: an answer may
 * carry a token or personal data.
 */
object HttpResponseMapper {

    fun map(
        response: Response,
        descriptor: OperationDescriptor,
        configName: String,
        path: String,
    ): ToolExecutionResult {
        val contentType = response.header(CONTENT_TYPE)
        val body = checkNotNull(response.body) { "an executed call always has a body" }
        val cap = BYTE_CAP_FACTOR * descriptor.shaping.maxResponseChars
        val bounded = BoundedBodyReader.read(body.source(), cap)
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        val text = bounded.bytes.toString(charset)
        val code = response.code
        val metadata = mutableMapOf<String, Any?>(
            "status" to code,
            "contentType" to contentType,
            "bytes" to bounded.bytes.size,
            "truncated" to bounded.truncated,
            "path" to path,
        )
        return when {
            code in SUCCESS -> success(
                code = code,
                text = text,
                contentType = contentType,
                bytesTruncated = bounded.truncated,
                response = response,
                descriptor = descriptor,
                metadata = metadata,
            )
            code in REDIRECTS -> failure(
                output = "HTTP $code: the target redirected the request; redirects are never followed.",
                errorType = HttpApiErrors.REDIRECT_NOT_FOLLOWED,
                metadata = metadata,
            )
            code == UNAUTHORIZED -> failure(
                output = "HTTP 401: the target rejected the credential from the bound Auth Setting. Do not retry; " +
                    "ask an administrator to check the Auth Setting of HTTP_API integration '$configName'.",
                errorType = HttpApiErrors.UNAUTHORIZED,
                metadata = metadata,
            )
            code == FORBIDDEN -> failure(
                output = "HTTP 403: the target refused this operation with the bound credential. Do not retry; " +
                    "ask an administrator to check the permissions granted to HTTP_API integration '$configName'.",
                errorType = HttpApiErrors.FORBIDDEN,
                metadata = metadata,
            )
            code == RATE_LIMITED -> failure(
                output = rateLimited(response),
                errorType = HttpApiErrors.RATE_LIMITED,
                metadata = metadata,
            )
            code in CLIENT_ERRORS -> failure(
                output = withBody(prefix = "HTTP $code", text = text),
                errorType = HttpApiErrors.HTTP_CLIENT_ERROR,
                metadata = metadata,
            )
            else -> failure(
                output = withBody(prefix = "HTTP $code: server error", text = text),
                errorType = HttpApiErrors.HTTP_SERVER_ERROR,
                metadata = metadata,
            )
        }
    }

    private fun success(
        code: Int,
        text: String,
        contentType: String?,
        bytesTruncated: Boolean,
        response: Response,
        descriptor: OperationDescriptor,
        metadata: MutableMap<String, Any?>,
    ): ToolExecutionResult {
        if (!ResponseShaper.isTextual(contentType)) {
            val length = response.body?.contentLength()?.takeIf { it >= 0 } ?: metadata["bytes"]
            return ToolExecutionResult.success("binary response ($contentType, $length bytes) not returned", metadata)
        }
        val shaped = text.takeIf { it.isNotEmpty() }
            ?.let { ResponseShaper.shape(it, contentType, bytesTruncated, descriptor.shaping) }
        metadata["truncated"] = shaped?.truncated ?: bytesTruncated
        val prefix = when (code) {
            CREATED -> "Created"
            ACCEPTED -> "Accepted"
            NO_CONTENT -> "No content"
            else -> null
        }
        val output = listOfNotNull(prefix, shaped?.text).joinToString("\n").ifEmpty { "No content" }
        return ToolExecutionResult.success(output, metadata)
    }

    /**
     * `Retry-After` is either a delay in seconds or an HTTP-date (RFC 9110): the message tells the model how
     * long to wait and, whatever the header says, not to retry in a loop.
     */
    private fun rateLimited(response: Response): String {
        val retryAfter = response.header(RETRY_AFTER)?.trim()?.takeIf { it.isNotEmpty() }
        val wait = when {
            retryAfter == null -> "wait before calling it again"
            retryAfter.all { it.isDigit() } -> "wait $retryAfter seconds before calling it again"
            else -> "wait until $retryAfter before calling it again"
        }
        return "HTTP 429: the target is rate limiting this integration: $wait, and do not hammer it with retries."
    }

    private fun withBody(prefix: String, text: String): String =
        if (text.isBlank()) "$prefix (no body)" else "$prefix: ${text.take(ERROR_BODY_CHARS)}"

    private fun failure(output: String, errorType: String, metadata: Map<String, Any?>): ToolExecutionResult =
        ToolExecutionResult(
            output = output,
            success = false,
            metadata = metadata,
            errorType = errorType,
            errorMessage = output,
        )

    private const val BYTE_CAP_FACTOR = 4L
    private const val ERROR_BODY_CHARS = 2000
    private const val CONTENT_TYPE = "Content-Type"
    private const val RETRY_AFTER = "Retry-After"
    private const val CREATED = 201
    private const val ACCEPTED = 202
    private const val NO_CONTENT = 204
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val RATE_LIMITED = 429
    private val SUCCESS = 200..299
    private val REDIRECTS = 300..399
    private val CLIENT_ERRORS = 400..499
}
