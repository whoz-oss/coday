package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement

/**
 * The parts of an OpenAPI 3.x document the plugin needs, with every local `$ref` already inlined.
 *
 * @property title `info.title`.
 * @property version `info.version`.
 * @property serverUrl `servers[0].url` when declared, with its `{variables}` replaced by their defaults.
 * @property apiKeyScheme The `apiKey` security scheme placed in a header or a query parameter, if any: the
 *   first one named by the top-level `security` requirements, else the first declared one.
 * @property operations Usable operations, in document order.
 * @property skipped Operations the reader could not make usable (e.g. external `$ref`), with the reason.
 */
data class OpenApiDocument(
    val title: String?,
    val version: String?,
    val serverUrl: String?,
    val apiKeyScheme: ApiKeyScheme?,
    val operations: List<OpenApiOperation>,
    val skipped: List<SkippedOperation>,
)

/** A `components.securitySchemes` entry of type `apiKey`; cookie placement is not supported. */
data class ApiKeyScheme(val placement: ApiKeyPlacement, val name: String)

/** An operation dropped by the reader; [key] is `<METHOD> <path>`. */
data class SkippedOperation(val key: String, val reason: String)

/**
 * One `paths.<path>.<method>` entry.
 *
 * @property parameters Path-level and operation-level parameters merged; the operation wins on `(name, in)`.
 * @property requestBody The JSON or form-urlencoded body when declared and supported.
 * @property unsupportedBody True when a body is declared with no supported media type.
 * @property successDescription Description of the first 2xx response.
 */
data class OpenApiOperation(
    val operationId: String?,
    val method: HttpMethod,
    val path: String,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    val parameters: List<OpenApiParameter>,
    val requestBody: OpenApiRequestBody?,
    val unsupportedBody: Boolean,
    val successDescription: String?,
) {
    /** `<METHOD> <path>`, the stable identifier of the operation inside its document. */
    val key: String
        get() = "$method $path"
}

/** A parameter with its schema after ref inlining (an empty object when the document declares none). */
data class OpenApiParameter(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean,
    val description: String?,
    val schema: JsonNode,
)

/** A request body with a supported media type and its schema after ref inlining. */
data class OpenApiRequestBody(
    val mediaType: String,
    val schema: JsonNode,
    val required: Boolean,
)

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

enum class ParameterLocation { PATH, QUERY, HEADER, COOKIE }
