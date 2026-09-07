package io.whozoss.agentos.skill

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class SkillServiceImpl(
    private val skillRepository: SkillRepository,
) : SkillService {

    // -------------------------------------------------------------------------
    // EntityService CRUD operations
    // -------------------------------------------------------------------------

    override fun create(entity: Skill): Skill {
        requireUniqueName(entity.namespaceId, entity.name, excludeId = null)
        return saveOrConflict(entity)
    }

    override fun update(entity: Skill): Skill {
        val existing = getById(entity.metadata.id)
        if (isFilesystemBacked(existing)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Filesystem-backed skill '${existing.name}' is read-only through the API.",
            )
        }
        requireUniqueName(entity.namespaceId, entity.name, excludeId = entity.metadata.id)
        return saveOrConflict(entity)
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Skill> = skillRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Skill> = skillRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        val existing = findById(id) ?: return false
        if (isFilesystemBacked(existing)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Filesystem-backed skill '${existing.name}' is read-only through the API.",
            )
        }
        return skillRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int = skillRepository.deleteByParent(parentId)

    override fun findPlatform(): List<Skill> = skillRepository.findPlatform()

    // -------------------------------------------------------------------------
    // Runtime execution discovery & shadowing
    // -------------------------------------------------------------------------

    override suspend fun findSkills(
        namespaceId: UUID,
        selectors: List<String>?,
    ): List<Skill> {
        if (selectors.isNullOrEmpty()) return emptyList()
        val allSkills = loadEffectiveSkills(namespaceId)
        return filterSkills(allSkills, selectors)
    }

    override suspend fun findSkillByName(
        namespaceId: UUID,
        name: String,
    ): Skill? = withContext(Dispatchers.IO) {
        skillRepository.findByNameInNamespace(namespaceId, name)
            ?: skillRepository.findByNameInNamespace(null, name)
    }

    private suspend fun loadEffectiveSkills(namespaceId: UUID): List<Skill> = withContext(Dispatchers.IO) {
        val namespaceSkills = skillRepository.findByNamespaceId(namespaceId)
        val platformSkills = skillRepository.findPlatform()

        val namespaceNames = namespaceSkills.mapTo(HashSet()) { it.name.lowercase() }
        val shadowedPlatform = platformSkills.filter { it.name.lowercase() !in namespaceNames }

        namespaceSkills + shadowedPlatform
    }

    /**
     * Filters [skills] based on [selectors].
     *
     * Selectors support:
     * - Wildcard: a single star entry matches all available skills.
     * - Recursive folder prefix: prefix ending with slash-star-star matches the prefix itself
     *   and all recursive descendants (paths starting with prefix slash).
     * - Single-level folder prefix: prefix ending with slash-star matches only direct child skills
     *   under the prefix directory (remainder after prefix slash contains no further slash segments).
     * - Exact match: exact relative path, path ending with slash SKILL.md, or frontmatter name (case-insensitive).
     * - DB-persisted skills with null skillRelativePath only match wildcard or exact name.
     */
    internal fun filterSkills(
        skills: List<Skill>,
        selectors: List<String>,
    ): List<Skill> {
        if (selectors.isEmpty()) return emptyList()
        if (selectors.contains("*")) return skills
        val matched = LinkedHashSet<Skill>()
        for (selector in selectors) {
            val normalized = selector.trim()
            val beforeCount = matched.size
            when {
                normalized.endsWith("/**") -> {
                    val prefix = normalized.removeSuffix("/**").trimStart('/')
                    if (prefix.isNotEmpty()) {
                        skills.filterTo(matched) { skill ->
                            val path = skill.skillRelativePath
                            path != null && (path == prefix || path.startsWith("$prefix/"))
                        }
                    }
                }
                normalized.endsWith("/*") -> {
                    val prefix = normalized.removeSuffix("/*").trimStart('/')
                    if (prefix.isNotEmpty()) {
                        skills.filterTo(matched) { skill ->
                            val path = skill.skillRelativePath
                            if (path == null || !path.startsWith("$prefix/")) {
                                false
                            } else {
                                val remainder = path.removePrefix("$prefix/")
                                remainder.isNotEmpty() && !remainder.contains('/')
                            }
                        }
                    }
                }
                else -> {
                    val candidate = normalized.removeSuffix("/SKILL.md").removeSuffix("/").trimStart('/')
                    val isDirectSkillMd = normalized == "SKILL.md"
                    skills.filterTo(matched) { skill ->
                        (isDirectSkillMd && skill.skillRelativePath == "") ||
                            (skill.skillRelativePath != null && skill.skillRelativePath.equals(candidate, ignoreCase = true)) ||
                            skill.name.equals(normalized, ignoreCase = true)
                    }
                }
            }
            if (matched.size == beforeCount) {
                logger.warn { "[SkillService] Skill selector '$selector' did not match any available skill in namespace" }
            }
        }
        return skills.filter { it in matched }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private fun isFilesystemBacked(skill: Skill): Boolean =
        skill.skillRelativePath != null || skill.resourceRoot != null

    private fun requireUniqueName(
        namespaceId: UUID?,
        name: String,
        excludeId: UUID?,
    ) {
        val conflict = skillRepository.findByNameInNamespace(namespaceId, name)
        if (conflict != null && conflict.metadata.id != excludeId) {
            val scope = namespaceId?.toString() ?: "platform"
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "A skill named '$name' already exists at scope '$scope' (id=${conflict.metadata.id}).",
            )
        }
    }

    private fun saveOrConflict(entity: Skill): Skill =
        try {
            skillRepository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            logger.warn {
                "[SkillService] doubleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, name='${entity.name}')"
            }
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "A skill named '${entity.name}' already exists in this scope.",
                e,
            )
        }

    companion object : KLogging()
}
