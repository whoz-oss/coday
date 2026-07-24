package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [CaseDefinition] entities.
 *
 * Four scope modes: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, user×namespace `(ns, user)`.
 * Authorization is enforced in [CaseDefinitionController].
 */
interface CaseDefinitionService : EntityService<CaseDefinition, UUID>, OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.CASE_DEFINITION
    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId

    /**
     * Find all non-removed platform-level case definitions (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatform(): List<CaseDefinition>

    /**
     * Resolves the effective set of case definitions for a given namespace + user context.
     *
     * Merges platform, namespace-shared, user-global and user×namespace layers by name.
     * Higher-priority layers override lower ones:
     * platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
     *
     * Access control is enforced in the Cypher query (DEPLOYED_TO graph traversal).
     *
     * @param namespaceId the namespace context
     * @param callerId the authenticated user's id
     */
    fun findEffective(namespaceId: UUID, callerId: UUID): List<CaseDefinition>

    /**
     * Find all non-removed case definitions at an exact scope level — no merge, no inheritance.
     * Scope is determined by the (namespaceId?, userId?) combination.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<CaseDefinition>

    /**
     * Toggle the [CaseDefinition.enabled] flag.
     *
     * @param id Definition identifier
     * @return The updated definition with enabled flipped
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [id] does not exist
     */
    fun toggle(id: UUID): CaseDefinition
}
