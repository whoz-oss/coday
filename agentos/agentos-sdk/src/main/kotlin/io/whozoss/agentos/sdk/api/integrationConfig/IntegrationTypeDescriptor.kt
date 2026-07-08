package io.whozoss.agentos.sdk.api.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * HTTP response item returned by `GET /api/integration-types` and
 * `GET /api/integration-types/{type}`.
 *
 * Describes a plugin integration type and its configuration schema. Clients can use
 * [configSchema] to render a dynamic configuration form for this integration type.
 *
 * @property type Machine-readable integration type identifier (e.g. `"GITHUB"`, `"JIRA"`).
 * @property displayName Human-readable name for display in the UI.
 * @property description Short description of what this integration provides.
 * @property configSchema JSON Schema describing the configuration object expected by
 *   [IntegrationConfigDto.parameters] for this type. Null when this integration requires
 *   no configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class IntegrationTypeDescriptor(
    val type: String,
    val displayName: String,
    val description: String,
    val configSchema: JsonNode?,
)
