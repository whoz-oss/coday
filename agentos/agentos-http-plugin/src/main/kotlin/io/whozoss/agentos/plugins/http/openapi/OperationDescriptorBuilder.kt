package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.OperationOverride

/**
 * Assembles an [OperationDescriptor] from a curated [OpenApiOperation], its tool suffix and the config
 * (defaults plus the matching `operations[]` override).
 */
object OperationDescriptorBuilder {

    /** @property schemaReduced True when [JsonSchemaBuilder] had to collapse deep schemas to fit its size limit. */
    data class Built(val descriptor: OperationDescriptor, val schemaReduced: Boolean)

    fun build(operation: OpenApiOperation, toolSuffix: String, config: HttpApiConfig): Built {
        val override = config.operations.firstOrNull { it.operationId == operation.operationId }
        val shaping = shaping(config, override)
        val schema = JsonSchemaBuilder.build(operation)
        val descriptor = OperationDescriptor(
            operationId = operation.operationId,
            method = operation.method,
            pathTemplate = operation.path,
            toolSuffix = toolSuffix,
            description = ToolDescription.of(
                operation,
                overrideText = override?.description,
                keepPaths = shaping.keepPaths,
            ),
            inputSchema = schema.json,
            parameters = operation.parameters
                .filter { it.location in JsonSchemaBuilder.EXPOSED_LOCATIONS }
                .map { parameterDescriptor(it) },
            body = operation.requestBody?.let { BodyDescriptor(mediaType = it.mediaType, required = it.required) },
            shaping = shaping,
        )
        return Built(descriptor = descriptor, schemaReduced = schema.reduced)
    }

    private fun shaping(config: HttpApiConfig, override: OperationOverride?): ResponseShaping =
        ResponseShaping(
            keepPaths = override?.keepPaths ?: emptyList(),
            ignorePaths = override?.ignorePaths ?: emptyList(),
            responseFormat = override?.responseFormat ?: config.responseFormat,
            maxResponseChars = override?.maxResponseChars ?: config.maxResponseChars,
        )

    private fun parameterDescriptor(parameter: OpenApiParameter): ParameterDescriptor =
        ParameterDescriptor(
            name = parameter.name,
            location = parameter.location,
            required = parameter.required,
            isArray = typeIncludes(parameter.schema, "array"),
        )

    /** True when the schema declares [type], either as its single `type` or inside a 3.1 type array. */
    private fun typeIncludes(schema: JsonNode, type: String): Boolean {
        val declared = schema.path("type")
        return if (declared.isArray) declared.any { it.asText() == type } else declared.asText() == type
    }
}
