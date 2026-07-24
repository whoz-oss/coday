package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [CaseDefinition].
 *
 * ### Scope model
 *
 * [namespaceId] is null for platform-level definitions; [userId] is null for shared definitions.
 * The combination `(namespaceId, userId)` encodes the four overlay layers:
 * - `(null, null)`   → platform
 * - `(null, user)`   → user-global
 * - `(ns, null)`     → namespace-shared
 * - `(ns, user)`     → user×namespace
 *
 * ### Edges (managed via Neo4jChildLinkService, no @Relationship fields)
 *
 * - `(:CaseDefinition)-[:BELONGS_TO]->(:Namespace)` when namespace-scoped
 * - `(:CaseDefinition)-[:BELONGS_TO]->(:AgentConfig)` always (agentConfigId is mandatory)
 *
 * ### Prompt reference
 *
 * [promptId] stores the ID of the generic Prompt created and managed automatically by the
 * backend. The linked Prompt MUST NOT have an agentConfigId (only generic prompts allowed).
 * This is an internal implementation detail — not exposed in the public API.
 *
 * Note: there is no `@Relationship` field. Edges are managed explicitly by
 * [Neo4jChildLinkService] via raw Cypher MERGE to avoid SDN eager hydration.
 *
 * ### Soft-delete convention
 *
 * [removed] is `null` for active records and `true` for soft-deleted ones.
 * Always filter with `WHERE NOT COALESCE(removed, false)`.
 *
 * ### tripleKey discriminator
 *
 * [tripleKey] is a denormalised discriminator for the unique business triple
 * `(namespaceId, userId, name)`, backed by a UNIQUE CONSTRAINT. Computed via
 * [OverlayKeyEncoding.activeKey]; rewritten to a tombstone on soft-delete so the
 * unique slot is freed immediately for re-creation.
 *
 * ### TODO: Cascade delete
 *
 * TODO: Cascade delete — When an AgentConfig is soft-deleted, its linked CaseDefinitions
 * should also be soft-deleted. This is straightforward for this case but the problem is
 * broader as other scheduler types with convergent structure will be added in the future.
 * Defer to a dedicated cleanup/lifecycle story.
 * See also: PromptNodeNeo4jRepository.softDeleteByAgentConfigId() as a pattern.
 */
@Node("CaseDefinition")
data class CaseDefinitionNode(
    @Id val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val agentConfigId: String,
    val promptId: String,
    val name: String,
    val description: String? = null,
    /** Standard 5-field cron expression, e.g. `"0 9 * * *"` or `"0 9 * * MON"`. */
    val cronExpression: String,
    val enabled: Boolean = true,
    val tripleKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): CaseDefinition =
        CaseDefinition(
            metadata = EntityMetadata(
                id = UUID.fromString(id),
                created = created,
                createdBy = createdBy,
                modified = modified,
                modifiedBy = modifiedBy,
                removed = removed ?: false,
                version = version,
            ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            agentConfigId = UUID.fromString(agentConfigId),
            promptId = UUID.fromString(promptId),
            name = name,
            description = description,
            cronExpression = cronExpression,
            enabled = enabled,
        )

    companion object {
        fun computeTripleKey(namespaceId: UUID?, userId: UUID?, name: String): String =
            OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(def: CaseDefinition): CaseDefinitionNode {
            val idString = def.id.toString()
            return CaseDefinitionNode(
                id = idString,
                namespaceId = def.namespaceId?.toString(),
                userId = def.userId?.toString(),
                agentConfigId = def.agentConfigId.toString(),
                promptId = def.promptId.toString(),
                name = def.name,
                description = def.description,
                cronExpression = def.cronExpression,
                enabled = def.enabled,
                tripleKey = computeTripleKey(def.namespaceId, def.userId, def.name),
                version = def.metadata.version,
                created = def.metadata.created,
                createdBy = def.metadata.createdBy,
                modified = def.metadata.modified,
                modifiedBy = def.metadata.modifiedBy,
                removed = def.metadata.removed.takeIf { it },
            )
        }
    }
}
