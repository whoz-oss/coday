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
 * Storage asymmetry: [skillRelativePath] and [resourceRoot] are filesystem-only properties
 * and are null for Neo4j-persisted skills.
 *
 * [skillRelativePath] is the path of the containing directory relative to the skills root
 * (e.g. `product/spec-writing`). The empty string means the skill lives directly in the
 * skills root. Null for DB-persisted skills.
 *
 * [resourceRoot] is the absolute path of the directory containing `SKILL.md`, used by
 * [SkillReadResourceTool] to resolve adjacent resource files. Null when the skill has no
 * bundled resources (e.g. DB-stored skills).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Skill(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val name: String,
    val description: String,
    val body: String,
    /** Relative path of the skill directory under the skills root. Null for DB-persisted skills. */
    val skillRelativePath: String? = null,
    /** Absolute path of the directory containing SKILL.md. Null when no resources are available. */
    val resourceRoot: String? = null,
) : Entity
