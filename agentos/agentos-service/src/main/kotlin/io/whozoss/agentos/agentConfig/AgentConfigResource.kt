package io.whozoss.agentos.agentConfig

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
    val advancedExecution: Boolean = false,
)
