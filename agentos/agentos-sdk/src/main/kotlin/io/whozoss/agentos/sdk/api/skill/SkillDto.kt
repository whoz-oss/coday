package io.whozoss.agentos.sdk.api.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for [io.whozoss.agentos.skill.Skill] entities.
 *
 * [namespaceId] is null for platform-level skills and non-null for namespace-scoped skills.
 *
 * [name], [description], and [body] are required fields.
 *
 * [resources] stores optional auxiliary files (templates, references, scripts) as a path-to-content map.
 *
 * [createdBy], [createdOn], [updatedBy], [updatedOn] are read-only audit fields
 * present in GET responses; ignored on write. [readOnly] follows the same convention.
 */
@Schema(name = "Skill")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillDto(
    val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    @field:NotBlank(message = "description must not be blank")
    val description: String,
    @field:NotBlank(message = "body must not be blank")
    val body: String,
    val resources: Map<String, String>? = null,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
    @field:Schema(
        description = "True when this skill is backed by a filesystem SKILL.md file and therefore " +
            "cannot be modified or deleted through the API. Always present in responses, ignored on " +
            "write. Reflects the storage origin, NOT the caller's permissions: a false value does not " +
            "by itself mean the caller may write.",
    )
    val readOnly: Boolean? = null,
)
