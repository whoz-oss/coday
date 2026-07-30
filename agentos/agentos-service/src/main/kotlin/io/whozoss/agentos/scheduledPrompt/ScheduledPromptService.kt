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
 *
 * ### Prompt lifecycle
 *
 * The service owns creation, update and deletion of the [io.whozoss.agentos.prompt.Prompt]
 * entity linked to each ScheduledPrompt:
 * - [createWithPrompt]: creates the linked Prompt then persists the ScheduledPrompt.
 * - [updateWithPrompt]: updates the linked Prompt content/name then persists the ScheduledPrompt.
 * - [deleteWithPrompt]: soft-deletes the ScheduledPrompt then deletes the linked Prompt.
 *
 * The prompt name follows the pattern `scheduled--{nameSlug}` (max 100 chars).
 *
 * ### Content resolution
 *
 * [findByIdWithContent] and [withContent] resolve the linked prompt content in a single
 * batch call, so controllers never need to inject [io.whozoss.agentos.prompt.PromptService].
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
     *
     * [agentConfigId] is an optional post-merge filter; null means no filter.
     */
    fun findEffective(namespaceId: UUID, callerId: UUID, agentConfigId: UUID? = null): List<ScheduledPrompt>

    /**
     * Find all non-removed scheduled prompts at an exact scope level — no merge, no inheritance.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt>

    /**
     * Enable a [ScheduledPrompt] (idempotent). Recalculates [ScheduledPrompt.nextRunAt] only
     * on the actual disabled→enabled transition, so the scheduler never fires a stale slot;
     * a call on an already-enabled prompt is a no-op that returns the entity unchanged.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [id] does not exist
     */
    fun enable(id: UUID): ScheduledPrompt

    /**
     * Disable a [ScheduledPrompt] (idempotent). A call on an already-disabled prompt is a
     * no-op that returns the entity unchanged.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [id] does not exist
     */
    fun disable(id: UUID): ScheduledPrompt

    /**
     * Create a [ScheduledPrompt] together with its linked Prompt.
     *
     * [promptContent] is the opening message sent to the agent.
     * The linked Prompt is created automatically with name `scheduled--{nameSlug}` (max 100 chars).
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [entity.agentConfigId] or namespace not found
     * @throws io.whozoss.agentos.exception.BadRequestException on validation failures
     * @throws io.whozoss.agentos.exception.ConflictException if name is already taken in this scope
     */
    fun createWithPrompt(entity: ScheduledPrompt, promptContent: String): Pair<ScheduledPrompt, String>

    /**
     * Update a [ScheduledPrompt] and its linked Prompt content/name.
     *
     * [promptContent] is the new opening message sent to the agent.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if the linked Prompt is not found
     * @throws io.whozoss.agentos.exception.ConflictException if the new name is already taken in this scope
     */
    fun updateWithPrompt(entity: ScheduledPrompt, promptContent: String): Pair<ScheduledPrompt, String>

    /**
     * Soft-delete a [ScheduledPrompt] and its linked Prompt.
     *
     * @return true if the entity was found and deleted, false otherwise
     */
    fun deleteWithPrompt(id: UUID): Boolean

    /**
     * Find a [ScheduledPrompt] by id and return it paired with its prompt content.
     * Returns null when the entity does not exist.
     *
     * @param withRemoved when true, includes soft-deleted entities
     */
    fun findByIdWithContent(id: UUID, withRemoved: Boolean = false): Pair<ScheduledPrompt, String>?

    /**
     * Resolve prompt content for a list of [ScheduledPrompt]s.
     * Uses a single batch [io.whozoss.agentos.prompt.PromptService.findByIds] call to avoid N+1 queries.
     */
    fun withContent(sps: List<ScheduledPrompt>): List<Pair<ScheduledPrompt, String>>
}
