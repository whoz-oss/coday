package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.util.SensitiveFileDetector
import io.whozoss.agentos.sdk.util.StringUtils
import mu.KLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Parser for individual filesystem skill files (`SKILL.md`) and their bundled auxiliary resources.
 *
 * Enforces:
 * - Single-level directory structure under skills root (flat layout: `skills/{name}/SKILL.md`)
 * - Canonical path containment validation to reject symlink traversal outside the skill root
 * - YAML frontmatter extraction (name and description)
 * - Name format and length boundaries
 * - Resource file enumeration with sensitive-file and binary/junk exclusion
 */
class SkillFileParser(
    private val yamlMapper: ObjectMapper,
) {

    /**
     * Parses a single `SKILL.md` file located at [file] relative to [directory] (the skills root).
     *
     * Returns null when the file cannot be parsed, exceeds boundaries, has invalid metadata, or escapes root.
     */
    fun parseSkillFile(
        directory: Path,
        file: Path,
    ): Skill? {
        val skillDir = file.parent

        val realSkillsRoot =
            try {
                directory.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot resolve skills root: $directory" }
                return null
            }
        val realSkillDir =
            try {
                skillDir.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot resolve skill directory: $skillDir" }
                return null
            }
        val realFile =
            try {
                file.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot resolve SKILL.md file: $file" }
                return null
            }

        // Path containment: reject symlink escapes
        if (!realSkillDir.startsWith(realSkillsRoot) || !realFile.startsWith(realSkillsRoot)) {
            logger.warn { "[SkillFileParser] Symlink escape rejected: $realFile" }
            return null
        }

        // Flat layout: skill directory must be directly inside skills root (depth exactly 1)
        val relativePath = realSkillsRoot.relativize(realSkillDir).toString().replace("\\", "/")
        val depth = if (relativePath.isEmpty()) 0 else relativePath.split("/").size
        if (depth > 1) {
            logger.debug { "[SkillFileParser] Skipping nested skill in flat layout ($depth > 1): $file" }
            return null
        }

        val fileSize =
            try {
                Files.size(file)
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot stat $file" }
                return null
            }
        if (fileSize > MAX_SKILL_FILE_BYTES) {
            logger.warn { "[SkillFileParser] Skipping oversized SKILL.md ($fileSize B): $file" }
            return null
        }

        val content =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot read $file" }
                return null
            }

        val (frontmatterYaml, body) = splitFrontmatterAndBody(content) ?: return null

        val model =
            try {
                yamlMapper.readValue(frontmatterYaml, SkillFrontmatter::class.java)
            } catch (e: Exception) {
                logger.debug(e) { "[SkillFileParser] Invalid YAML frontmatter in $file" }
                return null
            }

        val rawName = collapseWhitespace(model.name ?: "")
        val description = collapseWhitespace(model.description ?: "").truncate(MAX_SKILL_DESCRIPTION_CHARS)

        if (rawName.isBlank() || description.isBlank()) {
            logger.debug { "[SkillFileParser] Skipping $file: blank name or description" }
            return null
        }

        if (rawName.length > MAX_SKILL_NAME_CHARS || !isValidSkillName(rawName)) {
            logger.warn { "[SkillFileParser] Skipping $file: invalid skill name '$rawName' (must match alphanumeric/dash/underscore/dot/space, max $MAX_SKILL_NAME_CHARS chars)" }
            return null
        }

        val name = rawName
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
        val realSkillDir =
            try {
                skillDir.toRealPath()
            } catch (e: IOException) {
                logger.warn(e) { "[SkillFileParser] Cannot resolve skill directory for resources: $skillDir" }
                return resources
            }
        try {
            Files.walk(skillDir, MAX_RESOURCE_WALK_DEPTH).use { stream ->
                stream
                    // NOFOLLOW_LINKS: a symlink is never treated as a regular file here, even when
                    // its target is one — symlinks (including ones with an innocuous filename that
                    // would otherwise bypass the sensitive-file filter) are rejected outright.
                    .filter {
                        Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                            it.fileName.toString() != SKILL_FILE_NAME
                    }
                    .filter { !isJunkOrBinaryResource(skillDir.relativize(it)) }
                    .forEach { resourceFile ->
                        val fileName = resourceFile.fileName.toString()
                        // Defense in depth: even though symlinks are already excluded above,
                        // re-validate the canonical path stays contained within the skill directory
                        // before reading — protects against any remaining escape vector (e.g. a
                        // parent directory itself being replaced by a symlink between the walk and
                        // this read).
                        val realResourceFile =
                            try {
                                resourceFile.toRealPath()
                            } catch (e: IOException) {
                                logger.warn(e) { "[SkillFileParser] Cannot resolve resource path, skipping: $resourceFile" }
                                return@forEach
                            }
                        if (!realResourceFile.startsWith(realSkillDir)) {
                            logger.warn {
                                "[SkillFileParser] Symlink/path escape rejected for resource: " +
                                    "$resourceFile -> $realResourceFile"
                            }
                            return@forEach
                        }
                        if (!SensitiveFileDetector.isSensitive(fileName)) {
                            val relPath = StringUtils.normalizeRelativePath(skillDir.relativize(resourceFile).toString())
                            try {
                                if (Files.size(realResourceFile) <= SkillReadResourceTool.MAX_RESOURCE_BYTES) {
                                    resources[relPath] = Files.readString(realResourceFile)
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "[SkillFileParser] Could not read resource: $resourceFile" }
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[SkillFileParser] Error reading resources from $skillDir" }
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
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_NAME_CHARS = 100
        const val MAX_SKILL_DESCRIPTION_CHARS = 500
        const val MAX_SKILL_FILE_BYTES = 512 * 1024L // 512 KiB
        private const val MAX_RESOURCE_WALK_DEPTH = 5

        /** Allowed skill name characters: letters, digits, dash, underscore, dot, space. */
        private val SKILL_NAME_REGEX = Regex("^[a-zA-Z0-9_.-]+( [a-zA-Z0-9_.-]+)*$")

        private val JUNK_DIR_NAMES = setOf("__pycache__", "pycache", "node_modules")

        private val BINARY_RESOURCE_EXTENSIONS =
            setOf(
                "pyc", "class", "so", "dylib", "dll", "jar",
                "zip", "gz", "png", "jpg", "jpeg", "pdf",
            )

        fun isValidSkillName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_SKILL_NAME_CHARS && SKILL_NAME_REGEX.matches(name)
    }
}
