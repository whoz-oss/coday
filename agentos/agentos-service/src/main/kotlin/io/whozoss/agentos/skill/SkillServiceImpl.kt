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
    ): List<Skill> = withContext(Dispatchers.IO) {
        if (selectors.isNullOrEmpty()) return@withContext emptyList()
        if (selectors.contains("*")) {
            loadEffectiveSkills(namespaceId)
        } else {
            val names = selectors.map { it.trim().removeSuffix("/SKILL.md").removeSuffix("/").trimStart('/') }
            val matched = skillRepository.findByNamespaceIdAndNames(namespaceId, names)
            // Apply case-insensitive shadowing: namespace skills shadow platform skills of same name
            val namespaceSkills = matched.filter { it.namespaceId != null }
            val platformSkills = matched.filter { it.namespaceId == null }
            val namespaceNames = namespaceSkills.mapTo(HashSet()) { it.name.lowercase() }
            val shadowedPlatform = platformSkills.filter { it.name.lowercase() !in namespaceNames }
            namespaceSkills + shadowedPlatform
        }
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
     * In the flat skill model, selectors match:
     * - Wildcard `*`: matches all available skills.
     * - Exact skill name (case-insensitive).
     */
    internal fun filterSkills(
        skills: List<Skill>,
        selectors: List<String>,
    ): List<Skill> {
        if (selectors.isEmpty()) return emptyList()
        if (selectors.contains("*")) return skills
        val matched = LinkedHashSet<Skill>()
        for (selector in selectors) {
            val normalized = selector.trim().removeSuffix("/SKILL.md").removeSuffix("/").trimStart('/')
            val beforeCount = matched.size
            skills.filterTo(matched) { skill ->
                skill.name.equals(normalized, ignoreCase = true)
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

    /**
     * True when [skill] is backed by a filesystem SKILL.md rather than a persisted Neo4j node.
     *
     * [skillRepository] is [FilesystemSkillRepository] — a composite/decorator over the Neo4j
     * repository — so `skillRepository.findByIds(...)` ALWAYS resolves filesystem-backed skills
     * too (that's the whole point of the decorator). Checking "is the id absent from a
     * findByIds() call through that same composite" is therefore never true for a
     * filesystem-backed skill, and the read-only guard on [update]/[delete] would be silently
     * bypassed.
     *
     * The reliable discriminator is [io.whozoss.agentos.sdk.entity.EntityMetadata.version]:
     * filesystem skills are built in-memory on every read and never go through Spring Data
     * Neo4j's `save()`, so `metadata.version` — set to a non-null value by SDN on first
     * persistence — stays null for their entire lifetime. This mirrors the same idiom used by
     * [io.whozoss.agentos.agentConfig.AgentConfig.isFilesystemOnly] and
     * [io.whozoss.agentos.prompt.PromptServiceImpl.rejectIfFilesystemBacked].
     */
    private fun isFilesystemBacked(skill: Skill): Boolean = skill.metadata.version == null

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
