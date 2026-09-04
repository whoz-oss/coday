package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Discovers [Skill] definitions from `SKILL.md` files on the filesystem.
 *
 * Each skill lives in its own directory under `<configPath>/skills/`. The directory
 * name becomes the [Skill.skillRelativePath]. The `SKILL.md` file must start with a
 * YAML frontmatter block delimited by `---` lines, followed by the skill body.
 *
 * Example layout:
 * ```
 * <configPath>/skills/
 *   product/spec-writing/SKILL.md
 *   core/branch-creation/SKILL.md
 * ```
 *
 * Safety limits:
 * - [MAX_SKILL_FILE_BYTES]: files larger than this are skipped.
 * - [MAX_WALK_DEPTH]: accepted skill-directory depths are zero through three; the shared
 *   cache walk is bounded at file depth four so rejected deeper trees are never traversed.
 * - [MAX_SKILL_COUNT]: at most 500 unique, path-sorted skills are returned.
 * - [MAX_SKILL_NAME_CHARS]: names longer than this are truncated with an ellipsis.
 * - [MAX_SKILL_DESCRIPTION_CHARS]: same for descriptions.
 *
 * Discovery reuses [FilesystemYamlCacheRegistry] (the same caching mechanism as
 * [io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository] and
 * [io.whozoss.agentos.prompt.FilesystemPromptRepository]), with a custom [filePredicate]
 * matching the exact filename `SKILL.md` instead of the default YAML-extension predicate.
 * Results are cached per skills-root directory for a TTL to avoid repeated I/O.
 *
 * Symlinks that escape the skills root are rejected (path containment check).
 *
 * Deduplication: if two SKILL.md files produce the same name (case-insensitive),
 * the one with the lexicographically smaller [skillRelativePath] wins.
 *
 * @param yamlMapper Shared YAML mapper bean (qualified `yamlMapper`), consistent with every
 *   other filesystem repository in AgentOS. Never an inline mapper instance.
 * @param ttl Cache TTL per directory. Defaults to 5 minutes.
 */
@Component
class FilesystemSkillRepository(
    @param:Qualifier("yamlMapper") private val yamlMapper: ObjectMapper,
    private val ttl: Duration = Duration.ofMinutes(5),
) {
    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = ::parseSkillFile,
            ttl = ttl,
            filePredicate = { it.fileName.toString() == SKILL_FILE_NAME },
            // Accepted skill-directory depths are 0..MAX_WALK_DEPTH-1 relative to the skills
            // root (root itself is depth 0). SKILL.md sits one level below its skill directory,
            // so a SKILL.md at an accepted depth is at most MAX_WALK_DEPTH file-levels below the
            // root. Bounding Files.walk at MAX_WALK_DEPTH means anything deeper is never visited
            // at all — same net result as visiting-then-rejecting, without the wasted I/O.
            maxDepth = MAX_WALK_DEPTH,
        )

    /**
     * Returns all valid skills discovered under `<configPath>/skills/`.
     *
     * Results are cached per skills-root directory for [ttl]. A cache miss triggers a full
     * directory walk. Returns an empty list when the skills directory does not exist.
     *
     * Deduplication by name (case-insensitive) is applied here, after the cache returns raw
     * results: skills are sorted by [Skill.skillRelativePath] and the first occurrence of
     * each lowercased name wins. [MAX_SKILL_COUNT] caps the final deduplicated list —
     * duplicates removed by name never consume the count — with a single WARN when truncation
     * occurs.
     */
    fun findAll(configPath: String): List<Skill> {
        val skillsRoot = Path.of(configPath, SKILLS_SUBDIR)
        val all = cacheRegistry.getAll(skillsRoot).sortedBy { it.skillRelativePath }

        val seenNames = LinkedHashSet<String>() // lowercased name, first-by-path wins
        val result = mutableListOf<Skill>()
        for (skill in all) {
            val nameKey = skill.name.lowercase()
            if (seenNames.add(nameKey)) {
                if (result.size < MAX_SKILL_COUNT) {
                    result += skill
                }
            } else {
                logger.debug { "[FilesystemSkillRepository] Duplicate name '${skill.name}' at '${skill.skillRelativePath}' (kept an earlier path)" }
            }
        }
        if (seenNames.size > MAX_SKILL_COUNT) {
            logger.warn {
                "[FilesystemSkillRepository] Discovered ${seenNames.size} unique skills under $skillsRoot, " +
                    "exceeding MAX_SKILL_COUNT=$MAX_SKILL_COUNT; truncated to the first $MAX_SKILL_COUNT by path"
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Parsing (invoked by FilesystemYamlCacheRegistry per matched file)
    // -------------------------------------------------------------------------

    /**
     * Parses a single `SKILL.md` file into a [Skill], or null when the file should be skipped.
     *
     * [directory] is the skills root (`<configPath>/skills`), as passed by
     * [FilesystemYamlCacheRegistry]. [file] is the matched `SKILL.md` path, always one level
     * below its containing skill directory.
     *
     * Applies, in order: path containment (symlink escape rejection), depth guard
     * ([MAX_WALK_DEPTH]), file-size guard ([MAX_SKILL_FILE_BYTES]), frontmatter parsing,
     * and name and blank checks with whitespace collapsing and truncation.
     */
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

        // Path containment: reject symlink escapes, whether the escaping symlink is an
        // ancestor directory (realSkillDir check) or the SKILL.md file itself (realFile check).
        if (!realSkillDir.startsWith(realSkillsRoot) || !realFile.startsWith(realSkillsRoot)) {
            logger.warn { "[FilesystemSkillRepository] Symlink escape rejected: $realFile" }
            return null
        }

        // Depth check: skill directory must have fewer than MAX_WALK_DEPTH path segments
        // below the skills root. A depth equal to MAX_WALK_DEPTH is already too deep.
        val relativePath = realSkillsRoot.relativize(realSkillDir).toString().replace("\\", "/")
        val depth = if (relativePath.isEmpty()) 0 else relativePath.split("/").size
        if (depth >= MAX_WALK_DEPTH) {
            logger.debug { "[FilesystemSkillRepository] Skipping deep skill ($depth >= $MAX_WALK_DEPTH): $file" }
            return null
        }

        // File size guard.
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

        return Skill(
            name = name,
            description = description,
            body = body,
            skillRelativePath = relativePath,
            resourceRoot = realSkillDir.toString(),
        )
    }

    // -------------------------------------------------------------------------
    // Frontmatter parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Splits the file content into (frontmatterYaml, body).
     *
     * The file must start with `---` followed by a second `---` delimiter. A single blank
     * separator line immediately following the closing delimiter is stripped (the common
     * Claude-skill convention of a blank line between frontmatter and body); any further
     * blank lines are preserved verbatim as part of the body. Returns null when no valid
     * frontmatter is present.
     */
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

    // -------------------------------------------------------------------------
    // String helpers
    // -------------------------------------------------------------------------

    /** Collapses all whitespace sequences (including newlines) to a single space and trims. */
    private fun collapseWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    /**
     * Truncates to [maxChars] characters, appending an ellipsis when truncation occurs.
     * The returned string has length at most [maxChars] + 1 (the ellipsis character).
     */
    private fun String.truncate(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "\u2026"

    // -------------------------------------------------------------------------
    // YAML model
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SkillFrontmatter(
        val name: String? = null,
        val description: String? = null,
    )

    companion object : KLogging() {
        private const val SKILLS_SUBDIR = "skills"
        private const val SKILL_FILE_NAME = "SKILL.md"

        /** Maximum character count for [Skill.name] before truncation. */
        const val MAX_SKILL_NAME_CHARS = 100

        /** Maximum character count for [Skill.description] before truncation. */
        const val MAX_SKILL_DESCRIPTION_CHARS = 500

        /** Maximum byte size of a SKILL.md file. Files larger than this are skipped. */
        const val MAX_SKILL_FILE_BYTES = 512 * 1024L // 512 KiB

        /**
         * Maximum number of unique (post-dedup) skills returned by [findAll]. Duplicates removed
         * by name never consume this budget — the cap applies strictly to the deduplicated,
         * path-sorted result. Matches the prior `SkillResolver` precedent.
         */
        const val MAX_SKILL_COUNT = 500

        /**
         * Maximum accepted skill-directory depth below the skills root, exclusive.
         *
         * The skills root itself is depth 0. A skill directory directly inside it
         * (skills followed by a domain segment) is depth 1; nesting one level deeper
         * (skills, domain, skill-name) is depth 2. Accepted skill-directory depths are
         * therefore 0 through MAX_WALK_DEPTH minus 1 inclusive — a skill directory at
         * depth MAX_WALK_DEPTH or deeper is rejected. Since SKILL.md sits one file-level
         * below its skill directory, this also bounds the [FilesystemYamlCacheRegistry]
         * traversal depth passed as maxDepth: files below an accepted skill directory are
         * still reached, and anything deeper is never visited.
         */
        const val MAX_WALK_DEPTH = 4
    }
}
