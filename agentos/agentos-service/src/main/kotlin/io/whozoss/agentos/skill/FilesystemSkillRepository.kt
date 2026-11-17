package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.ConfigFile
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.plugin.filesystem.FilesystemEntry
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import io.whozoss.agentos.plugin.filesystem.filesystemEntityId
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

    /**
     * Every skill file of [namespaceId]'s `configPath` that the listings would load, with the persisted
     * skill of the namespace that masks it, if any (the rule of [findByNamespaceId]).
     */
    override fun configFiles(namespaceId: UUID): List<ConfigFile<Skill>> {
        val configPath = configPathOf(namespaceId) ?: return emptyList()
        val entries = distinctSkillEntries(Path.of(configPath, SKILLS_SUBDIR))
        if (entries.isEmpty()) return emptyList()
        val persisted = delegate.findByNamespaceId(namespaceId).associateBy { it.name.lowercase() }
        return entries
            .take(MAX_SKILL_COUNT)
            .map { ConfigFile(it.value.inNamespace(namespaceId), Path.of(configPath), it.file, persisted[it.value.name.lowercase()]) }
    }

    override fun reloadConfigFiles(namespaceId: UUID) {
        configPathOf(namespaceId)?.let { cacheRegistry.invalidate(Path.of(it, SKILLS_SUBDIR)) }
    }

    private fun configPathOf(namespaceId: UUID): String? = namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath

    private fun filesystemSkills(
        namespaceId: UUID,
        excludeNames: Set<String> = emptySet(),
    ): List<Skill> {
        val configPath = configPathOf(namespaceId) ?: return emptyList()
        val skillsRoot = Path.of(configPath, SKILLS_SUBDIR)
        val distinct = distinctSkillEntries(skillsRoot)
        if (distinct.size > MAX_SKILL_COUNT) {
            logger.warn {
                "[FilesystemSkillRepository] Discovered ${distinct.size} unique skills under $skillsRoot, " +
                    "exceeding MAX_SKILL_COUNT=$MAX_SKILL_COUNT; truncated to the first $MAX_SKILL_COUNT"
            }
        }
        return distinct
            .filter { it.value.name.lowercase() !in excludeNames }
            .take(MAX_SKILL_COUNT)
            .map { it.value.inNamespace(namespaceId) }
    }

    /** Skills under [skillsRoot] sorted by name, keeping the first of each (case-insensitive) name. */
    private fun distinctSkillEntries(skillsRoot: Path): List<FilesystemEntry<Skill>> {
        val seenNames = HashSet<String>()
        return cacheRegistry
            .getEntries(skillsRoot)
            .sortedBy { it.value.name }
            .filter { entry ->
                seenNames.add(entry.value.name.lowercase()).also { kept ->
                    if (!kept) logger.debug { "[FilesystemSkillRepository] Duplicate name '${entry.value.name}' (kept earlier)" }
                }
            }
    }

    private fun Skill.inNamespace(namespaceId: UUID): Skill =
        copy(
            metadata = EntityMetadata(id = filesystemEntityId(FilesystemEntityKind.SKILL, namespaceId, name)),
            namespaceId = namespaceId,
        )

    companion object : KLogging() {
        private const val SKILLS_SUBDIR = "skills"

        const val MAX_SKILL_COUNT = 500
        const val MAX_WALK_DEPTH = 2
    }
}
