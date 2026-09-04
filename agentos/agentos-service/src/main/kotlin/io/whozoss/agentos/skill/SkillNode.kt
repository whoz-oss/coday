package io.whozoss.agentos.skill

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Skill].
 *
 * Stored as `(:Skill)-[:BELONGS_TO]->(:Namespace)` for namespace-scoped skills.
 * Both the scalar [namespaceId] property and the [namespace] outgoing @Relationship
 * to [NamespaceNode] are maintained in sync by [Neo4jSkillRepository.save].
 *
 * Platform-level skills (`namespaceId == null`) have no `BELONGS_TO` edge.
 *
 * [namespace] is a nullable `var` so SDN can call the primary constructor before
 * property-injecting the @Relationship field.
 *
 * [doubleKey] is a denormalised, deterministic discriminator computed from
 * `(namespaceId, name.lowercase())`. It backs uniqueness per level and single-property
 * index seek for [SkillNodeNeo4jRepository.findActiveByDoubleKey].
 *
 * Storage asymmetry: [skillRelativePath] and [resourceRoot] are filesystem-only properties,
 * so they are not persisted to Neo4j.
 */
@Node("Skill")
data class SkillNode(
    @Id
    val id: String,
    val namespaceId: String? = null,
    val name: String,
    val doubleKey: String,
    val description: String,
    val body: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
) {
    fun toDomain(): Skill =
        Skill(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                    version = version,
                ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            name = name,
            description = description,
            body = body,
            skillRelativePath = null,
            resourceRoot = null,
        )

    companion object {
        fun computeDoubleKey(
            namespaceId: UUID?,
            name: String,
        ): String =
            (namespaceId?.toString() ?: OverlayKeyEncoding.NULL_ID_SENTINEL) +
                OverlayKeyEncoding.SEPARATOR +
                name.lowercase()

        fun tombstoneDoubleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(skill: Skill): SkillNode {
            val idString = skill.id.toString()
            val doubleKey =
                when {
                    skill.metadata.removed -> tombstoneDoubleKey(idString)
                    else -> computeDoubleKey(skill.namespaceId, skill.name)
                }
            return SkillNode(
                id = idString,
                namespaceId = skill.namespaceId?.toString(),
                name = skill.name,
                doubleKey = doubleKey,
                description = skill.description,
                body = skill.body,
                version = skill.metadata.version,
                created = skill.metadata.created,
                createdBy = skill.metadata.createdBy,
                modified = skill.metadata.modified,
                modifiedBy = skill.metadata.modifiedBy,
                removed = skill.metadata.removed.takeIf { it },
            )
        }
    }
}
