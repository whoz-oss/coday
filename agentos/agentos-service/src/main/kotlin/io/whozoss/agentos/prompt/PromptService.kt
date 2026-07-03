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
     * Scope-aware filtered listing used by [PromptController.list].
     *
     * Dispatches based on the resolved namespace/user filter combination:
     * - Specific namespace + no user request -> namespace-shared (guarded by [canReadNamespace])
     * - User requested -> user-scoped rows, optionally filtered by namespace
     * - No filters -> platform prompts
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     * @param canReadNamespace callback to check caller READ permission on the namespace
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        canReadNamespace: (UUID) -> Boolean,
    ): List<Prompt>
}
