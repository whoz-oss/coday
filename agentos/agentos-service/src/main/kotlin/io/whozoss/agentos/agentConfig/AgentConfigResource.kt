package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * HTTP resource (DTO) for [AgentConfig] entities.
 *
 * Annotated with @Schema(name = "AgentConfig") so that the generated OpenAPI spec
 * keeps the schema name "AgentConfig" instead of "AgentConfigResource".
 *
 * [namespaceId] is required — agent configs are always scoped to a namespace.
 * [name] is required — an agent must have a name.
 * [description], [instructions], [modelName], and [integrations] are optional.
 *
 * [integrations] maps integration names to an optional list of allowed tool names.
 * A null list means all tools from that integration are allowed.
 */
@Schema(name = "AgentConfig")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentConfigResource(
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
    /**
     * Opaque metadata map for external consumers (e.g. Copilot).
     * AgentOS persists this field as-is without interpreting its content.
     */
    val externalMetadata: Map<String, Any?>? = null,
    val createdBy: String? = null,
    val createdOn: Instant = Instant.now(),
    val updatedBy: String? = null,
    val updatedOn: Instant = Instant.now(),
    /** Whether this agent is published. Null on input is treated as false (unpublished). */
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
