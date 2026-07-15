package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [Prompt] entities.
 *
 * Four scope modes: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, user×namespace `(ns, user)`.
 * Authorization is enforced in [PromptController].
 */
interface PromptService : EntityService<Prompt, UUID>, OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.PROMPT
    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId

    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatform(): List<Prompt>

    /**
     * Find all non-removed prompts scoped to the given user,
     * regardless of [Prompt.namespaceId].
     */
    fun findByUserId(userId: UUID): List<Prompt>

    /**
     * Resolves the effective set of prompts for a given namespace + user context.
     *
     * Merges platform, namespace-shared, user-global and user×namespace layers by name.
     * Higher-priority layers override lower ones (same precedence as IntegrationConfig):
     * platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
     *
     * @param namespaceId the namespace context
     * @param callerId the authenticated user's id
     */
    fun findEffective(namespaceId: UUID, callerId: UUID): List<Prompt>

    /**
     * Find all non-removed prompts at an exact scope level — no merge, no inheritance.
     * Scope is determined by the (namespaceId?, userId?) combination.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<Prompt>
}
