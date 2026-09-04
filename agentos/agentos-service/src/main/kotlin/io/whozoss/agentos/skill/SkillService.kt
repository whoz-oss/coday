package io.whozoss.agentos.skill

import java.util.UUID

/**
 * Service for skill discovery.
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
interface SkillService {
    /**
     * Returns the skills accessible in [namespaceId] that match [selectors].
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
     * Scans the full catalogue. Returns null when [namespaceId] has no configPath
     * or when no skill matches.
     */
    suspend fun findSkillByName(
        namespaceId: UUID,
        name: String,
    ): Skill?
}
