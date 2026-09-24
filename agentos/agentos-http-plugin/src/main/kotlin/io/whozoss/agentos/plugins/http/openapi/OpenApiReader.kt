package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import org.yaml.snakeyaml.LoaderOptions

/**
 * Reads an OpenAPI 3.x document (JSON or YAML text) into an [OpenApiDocument].
 *
 * Network-free: the text is already loaded and the `$ref`s of parameters and request bodies are resolved
 * locally by [RefResolver]; response schemas are never expanded. An operation whose parameters or body
 * carry an unresolvable reference is reported in [OpenApiDocument.skipped].
 * The document is bounded by `maxBytes` both on the raw text and on the YAML loader.
 * Throws [IllegalArgumentException] when the text is too large, malformed, or not OpenAPI 3.x; the message
 * of a parse failure is a single line that never echoes the document source.
 */
object OpenApiReader {

    private val jsonMapper = ObjectMapper()

    fun read(text: String, maxBytes: Long): OpenApiDocument {
        val size = text.toByteArray(Charsets.UTF_8).size
        require(size <= maxBytes) { "OpenAPI document is $size bytes, larger than the allowed $maxBytes bytes" }
        val root = parse(text, maxBytes)
        requireOpenApi3(root)
        val resolver = RefResolver(root)
        val outcomes = root.path("paths").properties()
            .filter { (path, _) -> !path.startsWith(VENDOR_EXTENSION_PREFIX) }
            .flatMap { (path, pathItem) -> readPathItem(path, pathItem, resolver) }
        return OpenApiDocument(
            title = root.path("info").path("title").textOrNull(),
            version = root.path("info").path("version").textOrNull(),
            serverUrl = serverUrl(root.path("servers").path(0)),
            apiKeyScheme = apiKeyScheme(root),
            operations = outcomes.filterIsInstance<OperationOutcome.Usable>().map { it.operation },
            skipped = outcomes.filterIsInstance<OperationOutcome.Skipped>().map { it.skipped },
        )
    }

    /** `url` of a server object with every `{variable}` replaced by its declared default, when it has one. */
    private fun serverUrl(server: JsonNode): String? {
        val url = server.path("url").textOrNull() ?: return null
        val variables = server.path("variables")
        return SERVER_VARIABLE.replace(url) { match ->
            variables.path(match.groupValues[1]).path("default").textOrNull() ?: match.value
        }
    }

    /**
     * The `apiKey` scheme whose `in` is `header` or `query` and which names its parameter: the first one named
     * by the top-level `security` requirements, else the first one in `components.securitySchemes` order.
     */
    private fun apiKeyScheme(root: JsonNode): ApiKeyScheme? {
        val schemes = root.path("components").path("securitySchemes")
        val required = root.path("security").flatMap { requirement -> requirement.properties().map { it.key } }
        val candidates = required.map { schemes.path(it) } + schemes.properties().map { it.value }
        return candidates.firstNotNullOfOrNull(::usableApiKeyScheme)
    }

    private fun usableApiKeyScheme(scheme: JsonNode): ApiKeyScheme? {
        if (scheme.path("type").asText() != API_KEY_SCHEME) return null
        val name = scheme.path("name").textOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val placement = API_KEY_PLACEMENTS[scheme.path("in").asText()] ?: return null
        return ApiKeyScheme(placement = placement, name = name)
    }

    private fun parse(text: String, maxBytes: Long): JsonNode {
        val firstChar = text.firstOrNull { !it.isWhitespace() }
        val mapper = if (firstChar == '{' || firstChar == '[') jsonMapper else yamlMapper(maxBytes)
        val root = try {
            mapper.readTree(text)
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException("OpenAPI document cannot be parsed: ${firstLine(e)}", e)
        }
        require(root != null && root.isObject) { "OpenAPI document must be a JSON/YAML object" }
        return root
    }

    /**
     * The first line of the parser message only: a SnakeYAML mark echoes the offending source lines, which
     * must never reach a log, the failure registry or the agent (the document may sit next to secrets).
     */
    private fun firstLine(e: JsonProcessingException): String =
        e.originalMessage?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifEmpty { "malformed document" }

    private fun yamlMapper(maxBytes: Long): ObjectMapper {
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build())
    }

    private fun requireOpenApi3(root: JsonNode) {
        val openapi = root.path("openapi").textOrNull()
        require(openapi != null) {
            if (root.has("swagger")) {
                "Swagger ${root.path("swagger").asText()} documents are not supported: only OpenAPI 3.x is supported"
            } else {
                "Missing 'openapi' field: only OpenAPI 3.x is supported"
            }
        }
        require(openapi.startsWith("3.")) {
            "OpenAPI version '$openapi' is not supported: only OpenAPI 3.x is supported"
        }
    }

    /** One outcome per method declared on the path item, in [HttpMethod] order. */
    private fun readPathItem(path: String, pathItem: JsonNode, resolver: RefResolver): List<OperationOutcome> =
        HttpMethod.entries.mapNotNull { method ->
            pathItem.get(method.name.lowercase())?.let { operationNode ->
                readOutcome(
                    method = method,
                    path = path,
                    operationNode = operationNode,
                    rawPathParameters = pathItem.path("parameters"),
                    resolver = resolver,
                )
            }
        }

    private fun readOutcome(
        method: HttpMethod,
        path: String,
        operationNode: JsonNode,
        rawPathParameters: JsonNode,
        resolver: RefResolver,
    ): OperationOutcome =
        try {
            OperationOutcome.Usable(
                readOperation(
                    method = method,
                    path = path,
                    operation = operationNode,
                    rawPathParameters = rawPathParameters,
                    resolver = resolver,
                ),
            )
        } catch (e: UnresolvableRefException) {
            OperationOutcome.Skipped(SkippedOperation(key = "$method $path", reason = e.message ?: e.ref))
        }

    /**
     * Only the parts a tool consumes are ref-inlined: the parameters and the request body. Responses
     * contribute a single description string, so their schemas (often the bulk of a document) are
     * neither expanded nor able to make the operation unusable.
     */
    private fun readOperation(
        method: HttpMethod,
        path: String,
        operation: JsonNode,
        rawPathParameters: JsonNode,
        resolver: RefResolver,
    ): OpenApiOperation {
        val pathParameters = resolver.inline(rawPathParameters)
        val operationParameters = resolver.inline(operation.path("parameters"))
        val requestBody = resolver.inline(operation.path("requestBody"))
        val body = readRequestBody(requestBody)
        return OpenApiOperation(
            operationId = operation.path("operationId").textOrNull(),
            method = method,
            path = path,
            summary = operation.path("summary").textOrNull(),
            description = operation.path("description").textOrNull(),
            tags = operation.path("tags").map { it.asText() },
            deprecated = operation.path("deprecated").asBoolean(false),
            parameters = mergeParameters(pathParameters, operationParameters),
            requestBody = body,
            unsupportedBody = body == null && requestBody.path("content").isObject,
            successDescription = firstSuccessDescription(operation.path("responses"), resolver),
        )
    }

    private fun mergeParameters(pathLevel: JsonNode, operationLevel: JsonNode): List<OpenApiParameter> {
        val byKey = linkedMapOf<Pair<String, ParameterLocation>, OpenApiParameter>()
        (pathLevel.asSequence() + operationLevel.asSequence())
            .mapNotNull { readParameter(it) }
            .forEach { byKey[it.name to it.location] = it }
        return byKey.values.toList()
    }

    private fun readParameter(node: JsonNode): OpenApiParameter? {
        val name = node.path("name").textOrNull() ?: return null
        val location = ParameterLocation.entries
            .firstOrNull { it.name.equals(node.path("in").asText(), ignoreCase = true) }
            ?: return null
        return OpenApiParameter(
            name = name,
            location = location,
            required = location == ParameterLocation.PATH || node.path("required").asBoolean(false),
            description = node.path("description").textOrNull(),
            schema = node.get("schema") ?: JsonNodeFactory.instance.objectNode(),
        )
    }

    private fun readRequestBody(node: JsonNode): OpenApiRequestBody? {
        val content = node.path("content")
        if (!content.isObject) return null
        val mediaType = SUPPORTED_MEDIA_TYPES.firstOrNull { content.has(it) } ?: return null
        return OpenApiRequestBody(
            mediaType = mediaType,
            schema = content.path(mediaType).get("schema") ?: JsonNodeFactory.instance.objectNode(),
            required = node.path("required").asBoolean(false),
        )
    }

    private fun firstSuccessDescription(responses: JsonNode, resolver: RefResolver): String? {
        val response = responses.properties()
            .firstOrNull { (code, _) -> code.startsWith("2") && code.length == 3 }
            ?.value ?: return null
        val ref = response.path(REF).textOrNull() ?: return response.path("description").textOrNull()
        return resolver.localTarget(ref)?.path("description")?.textOrNull()
    }

    private fun JsonNode.textOrNull(): String? = if (isValueNode && !isNull) asText() else null

    /** What the reader made of one `paths.<path>.<method>` entry. */
    private sealed interface OperationOutcome {
        data class Usable(val operation: OpenApiOperation) : OperationOutcome
        data class Skipped(val skipped: SkippedOperation) : OperationOutcome
    }

    private const val VENDOR_EXTENSION_PREFIX = "x-"
    private const val REF = "\$ref"
    private val SERVER_VARIABLE = Regex("\\{([^}]+)}")
    private const val API_KEY_SCHEME = "apiKey"
    private val API_KEY_PLACEMENTS = mapOf("header" to ApiKeyPlacement.HEADER, "query" to ApiKeyPlacement.QUERY)

    /** In order of preference when a body declares several media types. */
    private val SUPPORTED_MEDIA_TYPES = listOf("application/json", "application/x-www-form-urlencoded")
}
