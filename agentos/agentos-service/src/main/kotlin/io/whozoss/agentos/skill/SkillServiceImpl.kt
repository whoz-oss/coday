package io.whozoss.agentos.skill

import io.whozoss.agentos.namespace.NamespaceService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of [SkillService].
 *
 * Skill discovery reads from the filesystem via [FilesystemSkillRepository].
 * The namespace's `configPath` determines the root directory.
 * Returns an empty list immediately when [selectors] is null or empty.
 *
 * Selector semantics (evaluated in order per selector):
 * - a bare star selector — all skills.
 * - a folder prefix followed by slash-star-star, or slash-star — folder prefix match on
 *   [Skill.skillRelativePath]. An empty prefix (bare slash-star-star or slash-star) matches
 *   nothing (safety guard).
 * - Exact path such as folder followed by skill name, or that same path with a trailing
 *   SKILL.md segment — matches [Skill.skillRelativePath] after stripping the trailing
 *   SKILL.md segment.
 * - Exact name such as skill-name — matches [Skill.name] case-insensitively.
 *
 * Unknown selectors log a warning and are silently ignored.
 * Discovery order is preserved; duplicates are removed (first occurrence wins).
 */
@Service
class SkillServiceImpl(
    private val skillRepository: FilesystemSkillRepository,
    private val namespaceService: NamespaceService,
) : SkillService {
    override suspend fun findSkills(
        namespaceId: UUID,
        selectors: List<String>?,
    ): List<Skill> {
        if (selectors.isNullOrEmpty()) return emptyList()
        val namespace = namespaceService.findById(namespaceId) ?: return emptyList()
        val configPath = namespace.configPath ?: return emptyList()
        val all = skillRepository.findAll(configPath)
        return filterSkills(all, selectors)
    }

    internal fun filterSkills(
        allSkills: List<Skill>,
        selectors: List<String>,
    ): List<Skill> {
        if (selectors.isEmpty()) return emptyList()

        val seen = LinkedHashSet<String>() // lowercased name — preserves discovery order
        val result = mutableListOf<Skill>()

        for (selector in selectors) {
            val matched = matchSelector(allSkills, selector)
            if (matched.isEmpty() && selector != "*" && !selector.endsWith("/**") && !selector.endsWith("/*")) {
                logger.warn { "[SkillService] selector '$selector' matched no skills" }
            }
            for (skill in matched) {
                if (seen.add(skill.name.lowercase())) {
                    result += skill
                }
            }
        }
        return result
    }

    override suspend fun findSkillByName(
        namespaceId: UUID,
        name: String,
    ): Skill? {
        val namespace = namespaceService.findById(namespaceId) ?: return null
        val configPath = namespace.configPath ?: return null
        val all = skillRepository.findAll(configPath)
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    // -------------------------------------------------------------------------
    // Selector matching
    // -------------------------------------------------------------------------

    /**
     * Returns the subset of [allSkills] that match [selector].
     *
     * Matching rules (evaluated in order):
     * 1. A bare star selector — all skills.
     * 2. A folder prefix followed by slash-star-star or slash-star — folder-prefix match on
     *    [Skill.skillRelativePath]. An empty prefix matches nothing.
     * 3. Exact path on [Skill.skillRelativePath] (with a trailing SKILL.md segment stripped).
     * 4. Exact name on [Skill.name] (case-insensitive).
     * 5. Special: the bare filename SKILL.md matches a root-level skill (skillRelativePath
     *    is empty).
     */
    private fun matchSelector(
        allSkills: List<Skill>,
        selector: String,
    ): List<Skill> {
        // 1. Wildcard
        if (selector == "*") return allSkills

        // 2. Folder glob
        val folderPrefix: String? =
            when {
                selector.endsWith("/**") -> selector.removeSuffix("/**")
                selector.endsWith("/*") -> selector.removeSuffix("/*")
                else -> null
            }
        if (folderPrefix != null) {
            if (folderPrefix.isEmpty()) return emptyList() // safety guard
            return allSkills.filter { it.skillRelativePath.startsWith("$folderPrefix/") }
        }

        // 3 + 5. Exact path (strip /SKILL.md suffix)
        val normalizedSelector = selector.removeSuffix("/SKILL.md")
        // Special case: bare "SKILL.md" selector targets root-level skill (skillRelativePath == "")
        val targetPath = if (selector == "SKILL.md") "" else normalizedSelector
        val byPath = allSkills.filter { it.skillRelativePath == targetPath }
        if (byPath.isNotEmpty()) return byPath

        // 4. Exact name (case-insensitive)
        return allSkills.filter { it.name.equals(normalizedSelector, ignoreCase = true) }
    }

    companion object : KLogging()
}
