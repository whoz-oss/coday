package io.whozoss.agentos.sdk.api.prompt

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for [io.whozoss.agentos.prompt.Prompt] entities.
 *
 * [namespaceId] is null for platform-level prompts and non-null for namespace-scoped prompts.
 *
 * On POST, scope is inferred from [namespaceId] and [userId]:
 * - (null, null)  → platform (Super Admin only)
 * - (ns, null)    → namespace-scoped (WRITE on namespace)
 * - (null, me)    → user-global (authenticated)
 * - (ns, me)      → user × namespace (READ on namespace)
 *
 * On PUT, [namespaceId], [userId] and [agentConfigId] are immutable
 * (preserved from the persisted entity).
 *
 * [agentConfigId] links this prompt to an [io.whozoss.agentos.agentConfig.AgentConfig].
 * When set, the prompt acts as a Starter for that agent. Immutable post-creation.
 *
 * [externalMetadata] is an opaque map persisted as-is by AgentOS.
 * External consumers (Copilot, Studio) store their own metadata here
 * (label, triggers, sections, etc.).
 *
 * [createdBy], [createdOn], [updatedBy], [updatedOn] are read-only audit fields
 * present in GET responses; ignored on write.
 */
@Schema(name = "Prompt")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PromptDto(
    val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val agentConfigId: UUID? = null,
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotEmpty
    @ArraySchema(schema = Schema(implementation = String::class, minLength = 1))
    val content: List<String>,
    @field:Valid val parameters: List<PromptParameterDto> = emptyList(),
    val externalMetadata: Map<String, Any?>? = null,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)

@Schema(name = "PromptParameter")
data class PromptParameterDto(
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotBlank val defaultValue: String,
)
