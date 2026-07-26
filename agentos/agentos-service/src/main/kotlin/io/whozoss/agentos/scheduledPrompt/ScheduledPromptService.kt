package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [ScheduledPrompt] entities.
 *
 * Four scope modes: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, user×namespace `(ns, user)`.
 * Authorization is enforced in [ScheduledPromptController].
 */
interface ScheduledPromptService : EntityService<ScheduledPrompt, UUID>, OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.SCHEDULED_PROMPT
    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId

    /** Find all non-removed platform-level scheduled prompts (namespaceId IS NULL AND userId IS NULL). */
    fun findPlatform(): List<ScheduledPrompt>

    /**
     * Resolves the effective set of scheduled prompts for a given namespace + user context.
     *
     * Merges platform, namespace-shared, user-global and user×namespace layers by name.
     * Higher-priority layers override lower ones:
     * platform (0) < user-global (1) < namespace-shared (2) < user×namespace (3).
     */
    fun findEffective(namespaceId: UUID, callerId: UUID): List<ScheduledPrompt>

    /**
     * Find all non-removed scheduled prompts at an exact scope level — no merge, no inheritance.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt>

    /**
     * Toggle the [ScheduledPrompt.enabled] flag.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [id] does not exist
     */
    fun toggle(id: UUID): ScheduledPrompt
}
