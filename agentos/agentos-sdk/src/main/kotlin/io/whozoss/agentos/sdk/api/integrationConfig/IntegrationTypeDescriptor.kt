package io.whozoss.agentos.sdk.api.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema

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
 * @property builtIn True for built-in integrations enabled by adding their [type] directly to an
 *   agent's `integrations` map (no [IntegrationConfig] instance). False for regular,
 *   instance-backed integration types.
 * @property enabledByDefault What this instance grants an agent that has made no choice about this
 *   type — i.e. whose `integrations` map does not mention it at all. An agent's own choice always
 *   wins, in both directions; this only fills the gap. Clients render it so the "platform default"
 *   state of a toggle can say which way it resolves. Always false for non-[builtIn] types, which
 *   are never granted implicitly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class IntegrationTypeDescriptor(
    val type: String,
    val displayName: String,
    val description: String,
    val configSchema: JsonNode?,
    val builtIn: Boolean = false,
    @field:Schema(
        description =
            "What this instance grants an agent whose integrations map does not mention this type at all. " +
                "An agent's own choice always wins, in both directions; this only fills the gap, so a client can " +
                "label the \"platform default\" state of a toggle. Always false for non-built-in types.",
    )
    val enabledByDefault: Boolean = false,
)
