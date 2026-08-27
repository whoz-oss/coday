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
     * [agentConfigId] is an optional post-merge filter: when provided, only prompts
     * linked to that agent are returned. When null, all resolved prompts are returned
     * (both agent-linked and autonomous).
     *
     * @param namespaceId the namespace context
     * @param callerId the authenticated user's id
     * @param agentConfigId optional post-merge filter
     */
    fun findEffective(namespaceId: UUID, callerId: UUID, agentConfigId: UUID? = null): List<Prompt>

    /**
     * Find all non-removed prompts at an exact scope level — no merge, no inheritance.
     * Scope is determined by the (namespaceId?, userId?) combination.
     * [agentConfigIds] is an optional filter; null or empty means no filter.
     */
    fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<Prompt>

    /**
     * Resolves a translation of [Prompt.content] and/or [Prompt.title] into [targetLanguage].
     *
     * Short-circuits when [targetLanguage] matches [Prompt.sourceLanguage] — returns the
     * source fields as-is without an LLM call. Returns cached translations when available.
     * Otherwise calls [PromptTranslationService], persists the results, and returns them.
     *
     * [namespaceId] or [namespaceExternalId] is required for AI model resolution.
     *
     * Returns a [PromptTranslation] carrying the resolved title and content for the
     * requested language.
     */
    fun translate(
        id: UUID,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): PromptTranslation
}

/**
 * Resolved translation for a single language, returned by [PromptService.translate].
 *
 * [title] is null when the prompt has no [Prompt.title].
 * [content] always has the same size as [Prompt.content].
 */
data class PromptTranslation(
    val title: String?,
    val content: List<String>,
)
