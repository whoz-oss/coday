package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Decorator over a delegate [SkillRepository] that augments read operations with [Skill]
 * definitions discovered from `SKILL.md` files under `<namespace.configPath>/skills/`.
 *
 * Each skill lives directly in its own directory under `<configPath>/skills/{skillName}/SKILL.md` (flat layout).
 * Auxiliary files (templates, references, scripts) in that directory are loaded into [Skill.resources].
 *
 * All write operations ([save], [delete], [deleteByParent]) are forwarded to the delegate
 * unchanged — the filesystem is never written.
 *
 * Collision rule: when a persisted skill carries the same name as a filesystem skill within
 * the same namespace (case-insensitive), the persisted skill wins and the filesystem entry is
 * dropped.
 *
 * Platform skills ([findPlatform]) are purely delegate-backed — the filesystem has no platform scope.
 *
 * Filesystem reads are cached per directory with a configurable [ttl] (default 5 minutes).
 */
class FilesystemSkillRepository(
    private val delegate: SkillRepository,
    private val namespaceRepository: NamespaceRepository,
    @param:Qualifier("yamlMapper") private val yamlMapper: ObjectMapper,
    ttl: Duration = Duration.ofMinutes(5),
) : SkillRepository by delegate {

    private val skillFileParser = SkillFileParser(yamlMapper)

    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = skillFileParser::parseSkillFile,
            ttl = ttl,
            filePredicate = { it.fileName.toString() == SkillFileParser.SKILL_FILE_NAME },
            maxDepth = MAX_WALK_DEPTH,
        )

    // -------------------------------------------------------------------------
    // Augmented read operations
    // -------------------------------------------------------------------------

    override fun findByParent(parentId: UUID): List<Skill> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<Skill> {
        val persisted = delegate.findByNamespaceId(namespaceId)
        val fromFilesystem = filesystemSkills(namespaceId, excludeNames = persisted.mapTo(HashSet()) { it.name.lowercase() })
        val merged = persisted + fromFilesystem
        logger.debug {
            "[FilesystemSkillRepository] namespace=$namespaceId: ${persisted.size} persisted + ${fromFilesystem.size} filesystem = ${merged.size} total"
        }
        return merged
    }

    override fun findPlatform(): List<Skill> = delegate.findPlatform()

    override fun findByNameInNamespace(
        namespaceId: UUID?,
        name: String,
    ): Skill? {
        val fromDelegate = delegate.findByNameInNamespace(namespaceId, name)
        if (fromDelegate != null) return fromDelegate
        if (namespaceId == null) return null
        return filesystemSkills(namespaceId).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    override fun findByNamespaceIdAndNames(
        namespaceId: UUID,
        names: Collection<String>,
    ): List<Skill> {
        if (names.isEmpty()) return emptyList()
        val nameSet = names.mapTo(HashSet()) { it.lowercase() }
        val persisted = delegate.findByNamespaceIdAndNames(namespaceId, names)

        // The delegate query matches (namespaceId = $namespaceId OR namespaceId IS NULL),
        // i.e. it also returns PLATFORM skills with a matching name. Only a persisted skill
        // that actually belongs to THIS namespace should shadow (exclude) a filesystem skill
        // of the same name — a platform skill with a matching name must not suppress the
        // namespace's own filesystem skill. Downstream namespace-over-platform shadowing is
        // still applied by SkillServiceImpl.findSkills on the merged result.
        val persistedNamespaceNames = persisted
            .filter { it.namespaceId == namespaceId }
            .mapTo(HashSet()) { it.name.lowercase() }

        val fromFilesystem = filesystemSkills(namespaceId, excludeNames = persistedNamespaceNames)
            .filter { it.name.lowercase() in nameSet }

        return persisted + fromFilesystem
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Skill> {
        val fromDelegate = delegate.findByIds(ids, withRemoved)
        val foundIds = fromDelegate.mapTo(HashSet()) { it.metadata.id }
        val missing = ids.filter { it !in foundIds }
        if (missing.isEmpty()) return fromDelegate

        val missingSet = missing.toHashSet()
        val seenFsIds = HashSet<UUID>()
        val fromFilesystem =
            namespaceRepository
                .findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
                .filter { it.configPath != null }
                .flatMap { namespace ->
                    filesystemSkills(namespace.metadata.id)
                        .filter { it.metadata.id in missingSet && seenFsIds.add(it.metadata.id) }
                }

        val allById = (fromDelegate + fromFilesystem).associateBy { it.metadata.id }
        return ids.mapNotNull { allById[it] }
    }

    // -------------------------------------------------------------------------
    // Filesystem discovery helpers
    // -------------------------------------------------------------------------

    private fun filesystemSkills(
        namespaceId: UUID,
        excludeNames: Set<String> = emptySet(),
    ): List<Skill> {
        val configPath =
            namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath
                ?: return emptyList()
        val skillsRoot = Path.of(configPath, SKILLS_SUBDIR)
        val all = cacheRegistry.getAll(skillsRoot).sortedBy { it.name }

        val seenNames = LinkedHashSet<String>() // lowercased name, first-by-path wins
        val result = mutableListOf<Skill>()
        for (skill in all) {
            val nameKey = skill.name.lowercase()
            if (seenNames.add(nameKey)) {
                if (nameKey !in excludeNames) {
                    if (result.size < MAX_SKILL_COUNT) {
                        result += skill.copy(
                            metadata = EntityMetadata(
                                id = computeFilesystemSkillId(namespaceId, skill.name),
                            ),
                            namespaceId = namespaceId,
                        )
                    }
                }
            } else {
                logger.debug { "[FilesystemSkillRepository] Duplicate name '${skill.name}' (kept earlier)" }
            }
        }
        if (seenNames.size > MAX_SKILL_COUNT) {
            logger.warn {
                "[FilesystemSkillRepository] Discovered ${seenNames.size} unique skills under $skillsRoot, " +
                    "exceeding MAX_SKILL_COUNT=$MAX_SKILL_COUNT; truncated to the first $MAX_SKILL_COUNT"
            }
        }
        return result
    }

    companion object : KLogging() {
        private const val SKILLS_SUBDIR = "skills"

        const val MAX_SKILL_COUNT = 500
        const val MAX_WALK_DEPTH = 2

        fun computeFilesystemSkillId(
            namespaceId: UUID,
            name: String,
        ): UUID = UUID.nameUUIDFromBytes("filesystem-skill:$namespaceId:$name".toByteArray(Charsets.UTF_8))
    }
}
