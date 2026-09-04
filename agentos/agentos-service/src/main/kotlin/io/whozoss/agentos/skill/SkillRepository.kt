package io.whozoss.agentos.skill

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Skill] persistence.
 *
 * Scoped to a namespace via nullable parent UUID (`namespaceId`), where null represents
 * platform-level skills.
 *
 * Storage layout (Neo4j): `(:Skill)-[:BELONGS_TO]->(:Namespace)` for namespace-scoped skills.
 * Platform-level skills (`namespaceId == null`) have no `BELONGS_TO` edge to a Namespace.
 */
interface SkillRepository : EntityRepository<Skill, UUID> {
    /**
     * Find all non-removed skills scoped to the given namespace, ordered by name ASC.
     */
    fun findByNamespaceId(namespaceId: UUID): List<Skill>

    /**
     * Find all non-removed platform-level skills (`namespaceId IS NULL`), ordered by name ASC.
     */
    fun findPlatform(): List<Skill>

    /**
     * Find a single non-removed skill matching the (namespaceId, name) pair case-insensitively.
     * When [namespaceId] is null, searches platform-level skills only.
     *
     * Uniqueness is enforced per level (namespace or platform).
     */
    fun findByNameInNamespace(
        namespaceId: UUID?,
        name: String,
    ): Skill?
}
