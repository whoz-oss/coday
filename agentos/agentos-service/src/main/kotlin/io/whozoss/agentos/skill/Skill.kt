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
) : Entity {
    /**
     * True when this skill is backed by a filesystem `SKILL.md` rather than a persisted Neo4j node.
     *
     * [SkillRepository] is implemented by [FilesystemSkillRepository] — a composite/decorator over
     * the Neo4j repository — so `findByIds(...)` ALWAYS resolves filesystem-backed skills too
     * (that's the whole point of the decorator). Checking "is the id absent from a findByIds()
     * call through that same composite" is therefore never true for a filesystem-backed skill,
     * and the read-only guard on [SkillService.update]/[SkillService.delete] would be silently
     * bypassed.
     *
     * The reliable discriminator is [EntityMetadata.version]: filesystem skills are built
     * in-memory on every read and never go through Spring Data Neo4j's `save()`, so
     * `metadata.version` — set to a non-null value by SDN on first persistence — stays null for
     * their entire lifetime. This is an explicit, named proxy for that fact, mirroring
     * [io.whozoss.agentos.agentConfig.AgentConfig.isFilesystemOnly] and
     * [io.whozoss.agentos.prompt.PromptServiceImpl.rejectIfFilesystemBacked]: callers should read
     * this property rather than re-deriving the same check at each call site.
     */
    val isFilesystemBacked: Boolean
        get() = metadata.version == null
}
