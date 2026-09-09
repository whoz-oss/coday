package io.whozoss.agentos.plugins.http.openapi

import io.whozoss.agentos.plugins.http.config.ResponseFormat

/**
 * Everything the HTTP layer needs to expose one curated operation as a tool and to execute it.
 *
 * @property toolSuffix Unique suffix inside the catalogue; the tool name is `<configName>__<toolSuffix>`.
 * @property description Tool description shown to the LLM (see [ToolDescription]).
 * @property inputSchema JSON Schema of the tool input (see [JsonSchemaBuilder]).
 * @property parameters Path, query and header parameters, in schema order.
 * @property body Request body contract, or null when the operation has none.
 * @property shaping Response filtering and rendering, resolved from the config and its per-operation override.
 */
data class OperationDescriptor(
    val operationId: String?,
    val method: HttpMethod,
    val pathTemplate: String,
    val toolSuffix: String,
    val description: String,
    val inputSchema: String,
    val parameters: List<ParameterDescriptor>,
    val body: BodyDescriptor?,
    val shaping: ResponseShaping,
)

/** @property isArray True when the parameter schema is an array (serialised as repeated query values). */
data class ParameterDescriptor(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean,
    val isArray: Boolean,
)

data class BodyDescriptor(
    val mediaType: String,
    val required: Boolean,
)

/** Response post-processing applied before the result reaches the LLM. */
data class ResponseShaping(
    val keepPaths: List<String>,
    val ignorePaths: List<String>,
    val responseFormat: ResponseFormat,
    val maxResponseChars: Int,
)
