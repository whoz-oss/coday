package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for AgentConfig entities — used as both request body and response body on
 * the `/api/agent-configs` endpoints.
 *
 * [namespaceId] and [name] are required on create. All other fields are optional.
 *
 * [integrations] maps integration type names to an optional list of allowed tool names.
 * A null list means all tools from that integration are allowed.
 *
 * [externalMetadata] is an opaque map that AgentOS persists as-is without interpreting
 * its content. Used by external consumers (e.g. Copilot) to store application-specific
 * metadata alongside the agent configuration.
 *
 * [enabled] controls whether the agent is published and visible to end-users.
 * Null on input is treated as false (unpublished) by the service.
 */
@Schema(name = "AgentConfig")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentConfigDto(
    val id: UUID? = null,
    /**
     * The namespace this agent belongs to. Null for platform-level agents.
     * Platform agents are visible across all namespaces and require super-admin to manage.
     */
    val namespaceId: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    val integrations: Map<String, List<String>?>? = null,
    val advancedExecution: Boolean? = null,
    val externalMetadata: Map<String, Any?>? = null,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
    val enabled: Boolean? = null,
    @field:Schema(
        description = "Glob patterns controlling which agents this agent may delegate to. " +
            "When null or empty, no delegation capability is provided. " +
            "'*' matches any sequence of characters (anchored, case-insensitive). " +
            "Examples: ['*'] allows all agents, ['*Fixer'] matches BugFixer/StoryFixer, " +
            "['Fixer*'] matches FixerHelper/FixerV2.",
    )
    val subAgents: List<String>? = null,
)
