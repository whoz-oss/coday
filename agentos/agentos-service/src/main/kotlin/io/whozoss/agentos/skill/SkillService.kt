package io.whozoss.agentos.skill

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for skill entity management and discovery.
 *
 * Implements [EntityService] for standard CRUD operations while providing specialized
 * discovery and resolution for runtime execution.
 *
 * [findSkills] is the primary entry point used by [io.whozoss.agentos.agent.AgentServiceImpl]
 * to resolve the skill catalogue for an agent run. It returns an empty list when [selectors]
 * is null or empty, matching the SkillServiceImpl semantics: null = no skills requested.
 *
 * [findSkillByName] resolves a skill by name at runtime (used by tool invocations).
 *
 * Selector matching is an implementation detail of [SkillServiceImpl] (`internal` method
 * `filterSkills`), not part of the public service contract.
 */
interface SkillService : EntityService<Skill, UUID> {
    /**
     * Returns the skills accessible in [namespaceId] that match [selectors].
     *
     * Resolves effective skills combining namespace-scoped and platform-level skills,
     * with namespace-scoped skills shadowing platform skills of the same name.
     *
     * Returns an empty list when [selectors] is null or empty (no skills requested).
     * Returns all skills when [selectors] contains `"*"`.
     *
     * The returned list is in discovery order with duplicates removed.
     */
    suspend fun findSkills(
        namespaceId: UUID,
        selectors: List<String>?,
    ): List<Skill>

    /**
     * Returns the skill matching [name] (case-insensitive) in [namespaceId], or null.
     *
     * Checks namespace-scoped skills first, then falls back to platform-level skills.
     */
    suspend fun findSkillByName(
        namespaceId: UUID,
        name: String,
    ): Skill?

    /**
     * Returns platform-level skills (namespaceId == null).
     */
    fun findPlatform(): List<Skill>
}
