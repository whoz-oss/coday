package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode

/**
 * Describes an integration type available in AgentOS.
 *
 * An [IntegrationTypeDescriptor] is a static, immutable description of a type of integration
 * (e.g. JIRA, GITHUB, SLACK). It is not a persisted entity — it is a catalogue entry that
 * tells the client:
 *   - which [type] identifier to use when creating an [IntegrationConfig]
 *   - what the integration does ([displayName], [description])
 *   - what parameters are expected, expressed as a JSON Schema ([configSchema])
 *
 * The [configSchema] follows JSON Schema draft-07 conventions and is intended to drive
 * dynamic form generation on the client side.
 *
 * Future evolution: each [io.whozoss.agentos.sdk.tool.ToolPlugin] will be able to contribute
 * its own descriptor via [IntegrationTypeRegistry]. For now the registry is hardcoded.
 */
data class IntegrationTypeDescriptor(
    /** Machine-readable identifier — matches [IntegrationConfig.integrationType]. */
    val type: String,
    /** Human-readable name for display in the UI. */
    val displayName: String,
    /** Short description of what this integration provides. */
    val description: String,
    /**
     * JSON Schema (draft-07) describing the expected shape of [IntegrationConfig.parameters].
     * The client uses this schema to render a configuration form dynamically. `null` for
     * built-in toggle integrations that take no configuration (see [builtIn]).
     */
    val configSchema: JsonNode? = null,
    /**
     * True for built-in integrations enabled by adding their [type] directly to an agent's
     * `integrations` map (no [IntegrationConfig] instance) — surfaced as a simple toggle in the
     * agent form. False for regular, instance-backed integration types.
     */
    val builtIn: Boolean = false,
)
