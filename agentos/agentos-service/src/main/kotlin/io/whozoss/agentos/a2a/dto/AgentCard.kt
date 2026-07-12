package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A2A `AgentCard` (spec §4.4.1) — the discovery document published per agent.
 *
 * Served at `/api/a2a/{namespaceId}/{agentName}/.well-known/agent-card.json`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentCard(
    val protocolVersion: String = "0.3.0",
    val name: String,
    val description: String,
    val url: String,
    val preferredTransport: String = "JSONRPC",
    val version: String = "0.1.0-prototype",
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val defaultInputModes: List<String> = listOf("text/plain"),
    val defaultOutputModes: List<String> = listOf("text/plain"),
    val skills: List<AgentSkill> = emptyList(),
    /**
     * Prototype: no security scheme declared — see docs/a2a.md.
     * When auth is added, populate `securitySchemes` + `security` per spec §4.5.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val provider: AgentProvider? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val documentationUrl: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val iconUrl: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val additionalInterfaces: List<AgentInterface>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentCapabilities(
    val streaming: Boolean = true,
    val pushNotifications: Boolean = false,
    val stateTransitionHistory: Boolean = false,
    val extensions: List<AgentExtension>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentExtension(
    val uri: String,
    val required: Boolean = false,
    val description: String? = null,
    val params: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentProvider(
    val organization: String,
    val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentInterface(
    val url: String,
    val transport: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_NULL) val examples: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val inputModes: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val outputModes: List<String>? = null,
)
