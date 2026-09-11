package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A skill that an agent can read and invoke.
 *
 * Reusable, on-demand instruction bundles with hybrid Neo4j-primary and filesystem-secondary storage.
 *
 * Scoped to a namespace via [namespaceId], or null for platform-level skills.
 *
 * [name] and [description] are whitespace-collapsed and truncated by the filesystem repository
 * before storage when parsed from files. [body] is the full markdown content returned verbatim
 * by [SkillReadTool].
 *
 * [resources] stores auxiliary resource files attached to the skill (e.g. templates, references, scripts)
 * as a map of relative path to text content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Skill(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val name: String,
    val description: String,
    val body: String,
    val resources: Map<String, String> = emptyMap(),
) : Entity
