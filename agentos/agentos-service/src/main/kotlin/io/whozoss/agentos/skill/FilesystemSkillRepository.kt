package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import java.io.IOException
import java.nio.file.Files
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

    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = ::parseSkillFile,
            ttl = ttl,
            filePredicate = { it.fileName.toString() == SKILL_FILE_NAME },
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
        val persistedNames = persisted.mapTo(HashSet()) { it.name.lowercase() }

        val fromFilesystem = filesystemSkills(namespaceId, excludeNames = persistedNames)
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

    // -------------------------------------------------------------------------
    // Parsing (invoked by FilesystemYamlCacheRegistry per matched file)
    // -------------------------------------------------------------------------

    private fun parseSkillFile(
        directory: Path,
        file: Path,
    ): Skill? {
        val skillDir = file.parent

        val realSkillsRoot =
            try {
                directory.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[FilesystemSkillRepository] Cannot resolve skills root: $directory" }
                return null
            }
        val realSkillDir =
            try {
                skillDir.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[FilesystemSkillRepository] Cannot resolve skill directory: $skillDir" }
                return null
            }
        val realFile =
            try {
                file.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[FilesystemSkillRepository] Cannot resolve SKILL.md file: $file" }
                return null
            }

        // Path containment: reject symlink escapes
        if (!realSkillDir.startsWith(realSkillsRoot) || !realFile.startsWith(realSkillsRoot)) {
            logger.warn { "[FilesystemSkillRepository] Symlink escape rejected: $realFile" }
            return null
        }

        // Flat layout: skill directory must be directly inside skills root (depth exactly 1)
        val relativePath = realSkillsRoot.relativize(realSkillDir).toString().replace("\\", "/")
        val depth = if (relativePath.isEmpty()) 0 else relativePath.split("/").size
        if (depth > 1) {
            logger.debug { "[FilesystemSkillRepository] Skipping nested skill in flat layout ($depth > 1): $file" }
            return null
        }

        val fileSize =
            try {
                Files.size(file)
            } catch (e: IOException) {
                logger.warn(e) { "[FilesystemSkillRepository] Cannot stat $file" }
                return null
            }
        if (fileSize > MAX_SKILL_FILE_BYTES) {
            logger.warn { "[FilesystemSkillRepository] Skipping oversized SKILL.md ($fileSize B): $file" }
            return null
        }

        val content =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                logger.warn(e) { "[FilesystemSkillRepository] Cannot read $file" }
                return null
            }

        val (frontmatterYaml, body) = splitFrontmatterAndBody(content) ?: return null

        val model =
            try {
                yamlMapper.readValue(frontmatterYaml, SkillFrontmatter::class.java)
            } catch (e: Exception) {
                logger.debug(e) { "[FilesystemSkillRepository] Invalid YAML frontmatter in $file" }
                return null
            }

        val name = collapseWhitespace(model.name ?: "").truncate(MAX_SKILL_NAME_CHARS)
        val description = collapseWhitespace(model.description ?: "").truncate(MAX_SKILL_DESCRIPTION_CHARS)

        if (name.isBlank() || description.isBlank()) {
            logger.debug { "[FilesystemSkillRepository] Skipping $file: blank name or description" }
            return null
        }

        val resources = loadSkillResources(realSkillDir)

        return Skill(
            metadata = EntityMetadata(
                id = UUID.nameUUIDFromBytes("filesystem-skill:$name".toByteArray(Charsets.UTF_8)),
            ),
            namespaceId = null,
            name = name,
            description = description,
            body = body,
            resources = resources,
        )
    }

    private fun loadSkillResources(skillDir: Path): Map<String, String> {
        val resources = mutableMapOf<String, String>()
        try {
            Files.walk(skillDir, MAX_RESOURCE_WALK_DEPTH).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() != SKILL_FILE_NAME }
                    .filter { !isJunkOrBinaryResource(skillDir.relativize(it)) }
                    .forEach { resourceFile ->
                        val fileName = resourceFile.fileName.toString()
                        if (!SkillReadResourceTool.isSensitiveFile(fileName)) {
                            val relPath = skillDir.relativize(resourceFile).toString().replace("\\", "/")
                            try {
                                if (Files.size(resourceFile) <= SkillReadResourceTool.MAX_RESOURCE_BYTES) {
                                    resources[relPath] = Files.readString(resourceFile)
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "[FilesystemSkillRepository] Could not read resource: $resourceFile" }
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[FilesystemSkillRepository] Error reading resources from $skillDir" }
        }
        return resources
    }

    /**
     * Excludes files that are expected to be unreadable as text or irrelevant as skill resources:
     * - any path segment that is a junk directory (`__pycache__`, `pycache`, `node_modules`) or
     *   hidden (starts with a dot)
     * - files with a known binary extension (compiled bytecode, native libs, archives, images, pdf)
     *
     * These are skipped silently: they are expected byproducts of skill tooling, not errors.
     */
    private fun isJunkOrBinaryResource(relativePath: Path): Boolean {
        val segments = (0 until relativePath.nameCount).map { relativePath.getName(it).toString() }
        if (segments.any { it in JUNK_DIR_NAMES || it.startsWith(".") }) return true
        val fileName = segments.last()
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0) return false
        return fileName.substring(dotIndex + 1).lowercase() in BINARY_RESOURCE_EXTENSIONS
    }

    private fun splitFrontmatterAndBody(content: String): Pair<String, String>? {
        if (!content.trimStart().startsWith("---")) return null
        val lines = content.lines()
        val firstDelimiter = lines.indexOfFirst { it.trim() == "---" }
        if (firstDelimiter < 0) return null
        val secondDelimiter = lines.drop(firstDelimiter + 1).indexOfFirst { it.trim() == "---" }
        if (secondDelimiter < 0) return null
        val fmEnd = firstDelimiter + 1 + secondDelimiter
        val frontmatterLines = lines.subList(firstDelimiter + 1, fmEnd)
        val bodyLines = lines.drop(fmEnd + 1)
        val trimmedBodyLines = if (bodyLines.firstOrNull()?.isBlank() == true) bodyLines.drop(1) else bodyLines
        val bodyText = trimmedBodyLines.joinToString("\n")
        return frontmatterLines.joinToString("\n") to bodyText
    }

    private fun collapseWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun String.truncate(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "\u2026"

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SkillFrontmatter(
        val name: String? = null,
        val description: String? = null,
    )

    companion object : KLogging() {
        private const val SKILLS_SUBDIR = "skills"
        private const val SKILL_FILE_NAME = "SKILL.md"

        const val MAX_SKILL_NAME_CHARS = 100
        const val MAX_SKILL_DESCRIPTION_CHARS = 500
        const val MAX_SKILL_FILE_BYTES = 512 * 1024L // 512 KiB
        const val MAX_SKILL_COUNT = 500
        const val MAX_WALK_DEPTH = 2
        private const val MAX_RESOURCE_WALK_DEPTH = 5

        private val JUNK_DIR_NAMES = setOf("__pycache__", "pycache", "node_modules")

        private val BINARY_RESOURCE_EXTENSIONS =
            setOf(
                "pyc", "class", "so", "dylib", "dll", "jar",
                "zip", "gz", "png", "jpg", "jpeg", "pdf",
            )

        fun computeFilesystemSkillId(
            namespaceId: UUID,
            name: String,
        ): UUID = UUID.nameUUIDFromBytes("filesystem-skill:$namespaceId:$name".toByteArray(Charsets.UTF_8))
    }
}
