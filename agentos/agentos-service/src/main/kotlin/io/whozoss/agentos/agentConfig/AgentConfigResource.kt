package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
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
    val published: Boolean? = null,
)
