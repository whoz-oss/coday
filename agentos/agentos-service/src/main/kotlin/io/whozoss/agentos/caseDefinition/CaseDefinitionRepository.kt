package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [CaseDefinition] persistence.
 *
 * [findByParent] returns only non-removed namespace-shared case definitions (userId IS NULL)
 * for the given namespace — user-scoped overlays are excluded.
 * Platform-level case definitions (namespaceId == null, userId == null) are retrieved via [findPlatform].
 */
interface CaseDefinitionRepository : EntityRepository<CaseDefinition, UUID> {

    /**
     * Find all non-removed platform-level case definitions (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatform(): List<CaseDefinition>

    /**
     * Find a single non-removed case definition matching the (namespaceId, userId, name) triple.
     * NULL values are matched literally.
     */
    fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): CaseDefinition?

    /**
     * Find all non-removed case definitions across the four overlay layers for the given
     * (namespaceId, userId) pair: platform, user-global, namespace-shared, user×namespace.
     * Returned in name-ascending order; callers are responsible for priority folding.
     *
     * Access control is enforced in the Cypher query: the user must be super-admin OR
     * a member of a UserGroup to which the agent is DEPLOYED_TO. There is no bifurcation
     * (unlike Prompt) because agentConfigId is always present.
     */
    fun findEffective(namespaceId: UUID, userId: UUID): List<CaseDefinition>

    /**
     * Find all non-removed case definitions at an exact scope level (no merge, no inheritance).
     * Scope is determined by the (namespaceId?, userId?) combination.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<CaseDefinition>
}
