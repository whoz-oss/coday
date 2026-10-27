package io.whozoss.agentos.sdk.api.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Compact, response-only projection of a Skill — name and description plus enough identity to
 * fetch the rest.
 *
 * Returned by `GET /api/skills/effective` (the candidate catalogue for
 * [io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto.skillSelectors]) and embedded in
 * [io.whozoss.agentos.sdk.api.agentConfig.AgentDefinitionDto.skills] (the skills an agent
 * actually resolved).
 *
 * **Deliberately excludes `body` and `resources`.** Both are unbounded — a skill body is full
 * markdown and the resources map holds the text of every bundled file — and a namespace may hold
 * up to 500 skills, so carrying them in a listing or in an agent definition would turn a picker
 * into a multi-megabyte download. Fetch `GET /api/skills/{id}` for the full entity.
 *
 * Audit fields are excluded for the same reason they are meaningless on filesystem-backed skills:
 * those are rebuilt in memory on every read, so their timestamps are the read time.
 */
@Schema(name = "SkillSummary")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillSummaryDto(
    val id: UUID,
    @field:Schema(
        types = ["string", "null"],
        format = "uuid",
        description = "Owning namespace, or null for a platform-level skill. Makes the winner of " +
            "namespace-over-platform shadowing explicit.",
    )
    val namespaceId: UUID? = null,
    @field:Schema(description = "The skill name, which is also the value a skillSelectors entry must match.")
    val name: String,
    val description: String,
    @field:Schema(
        description = "True when the skill is backed by a filesystem SKILL.md file and cannot be " +
            "modified through the API. Reflects the storage origin, NOT the caller's permissions.",
    )
    val readOnly: Boolean,
)
