package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.net.UrlCheck
import io.whozoss.agentos.plugins.http.openapi.HttpMethod
import io.whozoss.agentos.plugins.http.openapi.JsonSchemaBuilder
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor
import io.whozoss.agentos.plugins.http.openapi.ParameterLocation
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome of [HttpRequestFactory.build]. */
sealed interface RequestPlan {
    class Ready(val request: Request) : RequestPlan

    /** No request is sent; [errorType] and [message] go straight into the tool result. */
    class Rejected(val errorType: String, val message: String) : RequestPlan
}

/**
 * Turns the LLM arguments of one call into an OkHttp [Request].
 *
 * The URL is assembled with [HttpUrl.Builder] from the normalised base URL: each segment of the path
 * template has its `{name}` placeholders replaced by the argument value and is added as one path segment,
 * so `/` and other reserved characters are percent-encoded rather than spliced; a `.` or `..` segment is
 * refused since the builder would resolve it. Query parameters follow, the query-placed API key last.
 * The final URL must share the origin of the base URL, extend its path and pass the outbound policy.
 * Header parameters become request headers (see [buildHeaders]).
 */
object HttpRequestFactory {

    private sealed interface UrlBuild {
        class Ok(val url: HttpUrl) : UrlBuild
        class Rejected(val errorType: String, val message: String) : UrlBuild
    }

    private sealed interface BodyBuild {
        class Ok(val body: RequestBody?) : BodyBuild
        class Rejected(val message: String) : BodyBuild
    }

    private sealed interface HeadersBuild {
        class Ok(val headers: Map<String, String>) : HeadersBuild
        class Rejected(val message: String) : HeadersBuild
    }

    fun build(descriptor: OperationDescriptor, runtime: HttpApiRuntime, args: JsonNode): RequestPlan {
        val url = when (val built = buildUrl(descriptor, runtime, args)) {
            is UrlBuild.Ok -> built.url
            is UrlBuild.Rejected -> return RequestPlan.Rejected(errorType = built.errorType, message = built.message)
        }
        val body = when (val built = buildBody(descriptor, args)) {
            is BodyBuild.Ok -> built.body
            is BodyBuild.Rejected ->
                return RequestPlan.Rejected(errorType = HttpApiErrors.INVALID_INPUT, message = built.message)
        }
        val headers = when (val built = buildHeaders(descriptor, runtime, args)) {
            is HeadersBuild.Ok -> built.headers
            is HeadersBuild.Rejected ->
                return RequestPlan.Rejected(errorType = HttpApiErrors.INVALID_INPUT, message = built.message)
        }
        val request = Request.Builder()
            .url(url)
            .method(descriptor.method.name, body)
            .apply { headers.forEach { (name, value) -> header(name = name, value = value) } }
            .build()
        return RequestPlan.Ready(request)
    }

    private fun buildUrl(descriptor: OperationDescriptor, runtime: HttpApiRuntime, args: JsonNode): UrlBuild {
        val builder = runtime.baseUrl.newBuilder()
        for (template in descriptor.pathTemplate.split('/').filter { it.isNotEmpty() }) {
            val segment = when (val resolved = resolveSegment(template, args)) {
                is SegmentResolution.Ok -> resolved.segment
                is SegmentResolution.Rejected ->
                    return UrlBuild.Rejected(errorType = resolved.errorType, message = resolved.message)
            }
            builder.addPathSegment(segment)
        }
        descriptor.parameters
            .filter { it.location == ParameterLocation.QUERY }
            .forEach { parameter ->
                queryValues(args.get(parameter.name)).forEach { value ->
                    builder.addQueryParameter(name = parameter.name, value = value)
                }
            }
        val auth = runtime.authSpec
        if (auth is AuthHeaderSpec.Query) builder.addQueryParameter(name = auth.name, value = auth.value)
        val url = builder.build()
        if (!isUnder(url, base = runtime.baseUrl)) {
            return UrlBuild.Rejected(
                errorType = HttpApiErrors.URL_POLICY_REJECTED,
                message = "the request URL would leave the integration base URL",
            )
        }
        return when (val check = runtime.urlPolicy.validate(url.toString())) {
            is UrlCheck.Ok -> UrlBuild.Ok(url)
            is UrlCheck.Rejected -> UrlBuild.Rejected(
                errorType = HttpApiErrors.URL_POLICY_REJECTED,
                message = "the request URL ${check.reason}",
            )
        }
    }

    /** Same scheme, host and port as [base], and the path segments of [base] are a prefix of those of [url]. */
    private fun isUnder(url: HttpUrl, base: HttpUrl): Boolean {
        val basePath = base.encodedPathSegments.filter { it.isNotEmpty() }
        return url.scheme == base.scheme &&
            url.host == base.host &&
            url.port == base.port &&
            url.encodedPathSegments.take(basePath.size) == basePath
    }

    private sealed interface SegmentResolution {
        class Ok(val segment: String) : SegmentResolution
        class Rejected(val errorType: String, val message: String) : SegmentResolution
    }

    /** Replaces every `{name}` of [template] by the text of the argument; a missing argument is an input error. */
    private fun resolveSegment(template: String, args: JsonNode): SegmentResolution {
        val placeholders = PATH_PARAMETER.findAll(template).map { it.groupValues[1] }
        val missing = placeholders.firstOrNull { pathValue(args, it) == null }
        if (missing != null) {
            return SegmentResolution.Rejected(
                errorType = HttpApiErrors.INVALID_INPUT,
                message = "path parameter '$missing' is required and must not be blank",
            )
        }
        val segment = PATH_PARAMETER.replace(template) { match -> pathValue(args, match.groupValues[1]).orEmpty() }
        if (segment == "." || segment == "..") {
            return SegmentResolution.Rejected(
                errorType = HttpApiErrors.URL_POLICY_REJECTED,
                message = "path segment '$segment' would leave the base URL",
            )
        }
        return SegmentResolution.Ok(segment)
    }

    /** Text of the path argument [name]; null when absent, JSON null or blank. */
    private fun pathValue(args: JsonNode, name: String): String? =
        args.get(name)?.takeUnless { it.isNull }?.let(::textOf)?.takeIf { it.isNotBlank() }

    /** Texts of a query argument: a null or absent value contributes nothing, an array one value per element. */
    private fun queryValues(node: JsonNode?): List<String> =
        when {
            node == null || node.isNull -> emptyList()
            node.isArray -> node.filterNot { it.isNull }.map(::textOf)
            else -> listOf(textOf(node))
        }

    private fun textOf(node: JsonNode): String =
        if (node.isValueNode) node.asText() else HttpApiJson.mapper.writeValueAsString(node)

    /**
     * Default headers, then the header arguments of the call, then the auth header (which therefore wins),
     * `Accept` unless a default header sets it, and the user agent. A header argument named like a default
     * header is ignored: the administrator value is sent (the curator normally leaves no such argument). A
     * header argument must be a single line of printable ASCII: anything else (CR, LF, control or non-ASCII
     * characters) is refused before any request.
     */
    private fun buildHeaders(descriptor: OperationDescriptor, runtime: HttpApiRuntime, args: JsonNode): HeadersBuild {
        val headers = LinkedHashMap(runtime.defaultHeaders)
        for (parameter in descriptor.parameters.filter { it.location == ParameterLocation.HEADER }) {
            if (headers.keys.any { it.equals(parameter.name, ignoreCase = true) }) continue
            val node = args.get(parameter.name)?.takeUnless { it.isNull }
            if (node == null) {
                if (parameter.required) return HeadersBuild.Rejected("header parameter '${parameter.name}' is required")
                continue
            }
            if (!node.isValueNode || !HEADER_VALUE.matches(node.asText())) {
                return HeadersBuild.Rejected(
                    "header parameter '${parameter.name}' must be a single line of printable ASCII characters",
                )
            }
            headers[parameter.name] = node.asText()
        }
        val auth = runtime.authSpec
        if (auth is AuthHeaderSpec.Header) headers[auth.name] = auth.value
        if (headers.keys.none { it.equals(ACCEPT, ignoreCase = true) }) headers[ACCEPT] = ACCEPT_JSON
        headers[USER_AGENT] = "AgentOS-HTTP_API/${HttpApiTool.VERSION}"
        return HeadersBuild.Ok(headers)
    }

    private fun buildBody(descriptor: OperationDescriptor, args: JsonNode): BodyBuild {
        if (descriptor.method !in METHODS_WITH_BODY) return BodyBuild.Ok(null)
        val contract = descriptor.body ?: return BodyBuild.Ok(EMPTY_BODY)
        val value = args.get(JsonSchemaBuilder.BODY_PROPERTY)?.takeUnless { it.isNull }
        if (value == null) {
            return if (contract.required) {
                BodyBuild.Rejected("'${JsonSchemaBuilder.BODY_PROPERTY}' is required for this operation")
            } else {
                BodyBuild.Ok(EMPTY_BODY)
            }
        }
        return when (val encoded = RequestBodyEncoder.encode(value, contract.mediaType)) {
            is BodyEncoding.Encoded -> BodyBuild.Ok(encoded.body)
            is BodyEncoding.Invalid -> BodyBuild.Rejected(encoded.reason)
        }
    }

    private const val ACCEPT = "Accept"
    private const val ACCEPT_JSON = "application/json"
    private const val USER_AGENT = "User-Agent"
    private val PATH_PARAMETER = Regex("\\{([^}]+)}")
    private val HEADER_VALUE = Regex("[\\t\\x20-\\x7E]*")
    private val METHODS_WITH_BODY = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
}
