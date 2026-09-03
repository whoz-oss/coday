package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.io.path.pathString

data class Skill(
    val name: String,
    val description: String,
    val relativePath: String,
    val skillRelativePath: String,
)

/**
 * Discovers, parses, filters, and formats the skill catalog for a namespace.
 *
 * On each invocation, [discoverSkills] walks the `skills/` subdirectory of the namespace
 * configPath, parses the YAML frontmatter (`name`, `description`) of every `SKILL.md` file
 * it finds, and returns a bounded, path-sorted list of [Skill] entries. [filterSkills] applies
 * the [io.whozoss.agentos.agentConfig.AgentConfig.skillSelectors] tri-state logic to that list.
 * [buildSkillsBlock] combines both into the catalog text injected into the agent's instructions.
 *
 * Trust boundary: injecting skill `name` and `description` into agent instructions does not
 * introduce a new privilege boundary — anyone who can write `<configPath>/skills/` already holds
 * write access to the agent YAML files under `<configPath>/agents/`, which carry raw instruction
 * text. Both are
 * administrator-level namespace configuration. See [io.whozoss.agentos.agentConfig.AgentConfig.skillSelectors]
 * for the full trust and upgrade-semantics contract.
 */
@Component
class SkillResolver {

    private val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    // Simple TTL cache keyed by normalized configPath string.
    // Avoids N independent filesystem walks per namespace per minute under concurrent load.
    private data class CachedCatalog(val skills: List<Skill>, val computedAt: Instant)

    private val cache = ConcurrentHashMap<String, CachedCatalog>()

    suspend fun discoverSkills(namespaceConfigPath: String?): List<Skill> {
        if (namespaceConfigPath.isNullOrBlank()) return emptyList()

        // Normalize to absolute before deriving projectRoot so relative single-segment
        // paths (e.g. "coday") don't yield a null parent and fall back to the wrong base.
        val configPath = Path.of(namespaceConfigPath).toAbsolutePath().normalize()
        val cacheKey = configPath.pathString

        // Return cached result if still within TTL.
        val cached = cache[cacheKey]
        if (cached != null && Duration.between(cached.computedAt, Instant.now()) < CACHE_TTL) {
            return cached.skills
        }

        val skills = computeSkills(configPath)
        cache[cacheKey] = CachedCatalog(skills, Instant.now())
        return skills
    }

    // All filesystem I/O is inside withContext(Dispatchers.IO).
    private suspend fun computeSkills(configPath: Path): List<Skill> =
        withContext(Dispatchers.IO) {
            val skillsRoot = configPath.resolve(SKILLS_SUBDIR)

            val canonicalSkillsRoot =
                try {
                    skillsRoot.toRealPath()
                } catch (e: NoSuchFileException) {
                    // Normal case: no skills directory for this namespace.
                    logger.debug { "[SkillResolver] Skills directory does not exist: $skillsRoot" }
                    return@withContext emptyList()
                } catch (e: IOException) {
                    logger.warn(e) { "[SkillResolver] Could not resolve canonical path for skills root: $skillsRoot" }
                    return@withContext emptyList()
                }

            val skillFiles = mutableListOf<Path>()
            var totalSeen = 0

            // Attempt the walk directly; catch missing/non-directory as expected empty states.
            // Bounded walk depth.
            try {
                Files.walk(skillsRoot, MAX_WALK_DEPTH).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.name == SKILL_FILE_NAME }
                        .forEach { file ->
                            // Stop collecting after MAX_SKILL_COUNT.
                            if (skillFiles.size >= MAX_SKILL_COUNT) {
                                totalSeen++
                                return@forEach
                            }
                            if (isContainedInRoot(file, canonicalSkillsRoot)) {
                                skillFiles.add(file)
                                totalSeen++
                            } else {
                                logger.warn { "[SkillResolver] Skipping $file: escapes canonical boundary of $canonicalSkillsRoot" }
                            }
                        }
                }
            } catch (e: NoSuchFileException) {
                // Absent skills directory is the normal case — DEBUG, not WARN.
                logger.debug { "[SkillResolver] Skills directory not found: $skillsRoot" }
                return@withContext emptyList()
            } catch (e: NotDirectoryException) {
                // Path exists but is not a directory — also expected, DEBUG.
                logger.debug { "[SkillResolver] Skills path is not a directory: $skillsRoot" }
                return@withContext emptyList()
            } catch (e: Exception) {
                logger.warn(e) { "[SkillResolver] Error walking skills directory: $skillsRoot" }
                return@withContext emptyList()
            }

            // Warn once if the catalog was truncated.
            if (totalSeen > MAX_SKILL_COUNT) {
                logger.warn { "[SkillResolver] Skill catalog truncated to $MAX_SKILL_COUNT entries; $totalSeen SKILL.md files were found under $skillsRoot" }
            }

            // Derive projectRoot from the already-normalized absolute configPath.
            val projectRoot = configPath.parent ?: configPath
            val sortedFiles = skillFiles.sortedBy { projectRoot.relativize(it).pathString.replace('\\', '/') }

            val discoveredSkills = mutableListOf<Skill>()
            val seenNames = mutableSetOf<String>()

            for (file in sortedFiles) {
                val relativePath = projectRoot.relativize(file).pathString.replace('\\', '/')
                // Compute skillRelPath as the path of the containing directory relative to
                // skillsRoot, which is empty string for root-level skills.
                val skillRelPath = skillsRoot.relativize(file.parent ?: file).pathString.replace('\\', '/')
                val skill = parseSkillFile(file, relativePath, skillRelPath) ?: continue

                val normalizedName = skill.name.lowercase()
                if (normalizedName in seenNames) {
                    logger.warn { "[SkillResolver] Skipping duplicate skill name '${skill.name}' at $relativePath" }
                    continue
                }

                seenNames.add(normalizedName)
                discoveredSkills.add(skill)
            }

            discoveredSkills
        }

    fun filterSkills(
        skills: List<Skill>,
        selectors: List<String>?,
    ): List<Skill> {
        if (selectors == null) return skills
        if (selectors.isEmpty()) return emptyList()

        val matchingSkills = mutableSetOf<Skill>()

        for (rawSelector in selectors) {
            val selector = rawSelector.trim()
            if (selector.isBlank()) continue

            val matched = skills.filter { skill -> matchesSelector(skill, selector) }
            if (matched.isEmpty()) {
                logger.warn { "[SkillResolver] Skill selector '$selector' matched 0 discovered skills." }
            } else {
                matchingSkills.addAll(matched)
            }
        }

        return skills.filter { it in matchingSkills }
    }

    // suspend — wraps discoverSkills (which is already suspend).
    suspend fun buildSkillsBlock(
        namespaceConfigPath: String?,
        skillSelectors: List<String>? = null,
    ): String? {
        val allSkills = discoverSkills(namespaceConfigPath)
        val filteredSkills = filterSkills(allSkills, skillSelectors)
        if (filteredSkills.isEmpty()) return null

        return buildString {
            appendLine()
            appendLine("## Available Skills")
            appendLine()
            appendLine("You have access to the following domain skills. When a task matches a skill's description, read its `SKILL.md` using file tools to retrieve instructions and follow its guidelines on demand.")
            appendLine()
            filteredSkills.forEach { skill ->
                appendLine("- **${skill.name}** (`${skill.relativePath}`): ${skill.description}")
            }
            appendLine()
            appendLine("### Skill Activation Protocol")
            appendLine("1. Identify relevant skills from the catalog above based on the task description.")
            appendLine("2. Read the corresponding `SKILL.md` file using your file tools before executing skill-specific workflows.")
            appendLine("3. Follow instructions, scripts, or references defined in the skill documentation on-demand.")
        }.trimEnd()
    }

    private fun matchesSelector(skill: Skill, selector: String): Boolean {
        if (selector == "*") return true

        val normalizedSelector = selector.replace('\\', '/').trimEnd('/')

        if (normalizedSelector.endsWith("/**") || normalizedSelector.endsWith("/*")) {
            val prefix = normalizedSelector.substringBeforeLast("/*").substringBeforeLast("/**")
            // Guard against empty prefix matching everything.
            if (prefix.isEmpty()) return false
            return skill.skillRelativePath.equals(prefix, ignoreCase = true) ||
                skill.skillRelativePath.startsWith("$prefix/", ignoreCase = true)
        }

        if (skill.skillRelativePath.equals(normalizedSelector, ignoreCase = true)) {
            return true
        }

        // Build the selector-comparison path correctly for root-level skills
        // (skillRelativePath == "") so "SKILL.md" matches rather than "/SKILL.md".
        val selectorComparisonPath =
            if (skill.skillRelativePath.isEmpty()) {
                SKILL_FILE_NAME
            } else {
                "${skill.skillRelativePath}/$SKILL_FILE_NAME"
            }
        if (selectorComparisonPath.equals(normalizedSelector, ignoreCase = true)) {
            return true
        }

        if (skill.relativePath.equals(normalizedSelector, ignoreCase = true)) {
            return true
        }

        if (skill.name.equals(normalizedSelector, ignoreCase = true)) {
            return true
        }

        return false
    }

    private fun parseSkillFile(
        file: Path,
        relativePath: String,
        skillRelativePath: String,
    ): Skill? {
        // Guard against oversized files before reading into memory.
        val fileSize =
            try {
                Files.size(file)
            } catch (e: Exception) {
                logger.warn(e) { "[SkillResolver] Could not stat skill file: $file" }
                return null
            }
        if (fileSize > MAX_SKILL_FILE_BYTES) {
            logger.warn { "[SkillResolver] Skipping $relativePath: file size ${fileSize}B exceeds limit of ${MAX_SKILL_FILE_BYTES}B" }
            return null
        }

        val content =
            try {
                Files.readString(file)
            } catch (e: Exception) {
                logger.warn(e) { "[SkillResolver] Could not read skill file: $file" }
                return null
            }

        val frontmatter = extractFrontmatter(content)
        if (frontmatter == null) {
            logger.warn { "[SkillResolver] Skipping $relativePath: missing or malformed YAML frontmatter" }
            return null
        }

        val model =
            try {
                yamlMapper.readValue(frontmatter, SkillFrontmatterModel::class.java)
            } catch (e: JsonProcessingException) {
                logger.warn { "[SkillResolver] Skipping $relativePath: failed to parse YAML frontmatter: ${e.originalMessage ?: e.message}" }
                return null
            } catch (e: Exception) {
                logger.warn { "[SkillResolver] Skipping $relativePath: unexpected error reading YAML frontmatter: ${e.message}" }
                return null
            }

        if (model.name.isNullOrBlank()) {
            logger.warn { "[SkillResolver] Skipping $relativePath: required field 'name' is missing or blank" }
            return null
        }

        if (model.description.isNullOrBlank()) {
            logger.warn { "[SkillResolver] Skipping $relativePath: required field 'description' is missing or blank" }
            return null
        }

        // Collapse internal whitespace runs (including newlines from folded YAML scalars)
        // to a single space, then trim and truncate. Collapsing happens AFTER YAML parsing so
        // folded scalars (description: >) are resolved correctly before normalization.
        val normalizedName = collapseWhitespace(model.name).let { n ->
            if (n.length > MAX_SKILL_NAME_CHARS) n.take(MAX_SKILL_NAME_CHARS) + "\u2026" else n
        }
        val normalizedDescription = collapseWhitespace(model.description).let { d ->
            if (d.length > MAX_SKILL_DESCRIPTION_CHARS) d.take(MAX_SKILL_DESCRIPTION_CHARS) + "\u2026" else d
        }

        return Skill(
            name = normalizedName,
            description = normalizedDescription,
            relativePath = relativePath,
            skillRelativePath = skillRelativePath,
        )
    }

    private fun collapseWhitespace(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    private fun extractFrontmatter(content: String): String? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null

        val lines = trimmed.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") return null

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex == -1) return null

        return lines.subList(1, endIndex + 1).joinToString("\n")
    }

    private fun isContainedInRoot(file: Path, canonicalRoot: Path): Boolean =
        try {
            val canonicalFile = file.toRealPath()
            canonicalFile.startsWith(canonicalRoot)
        } catch (e: NoSuchFileException) {
            false
        } catch (e: IOException) {
            false
        }

    companion object : KLogging() {
        const val SKILLS_SUBDIR = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"

        // Max bytes read per SKILL.md before skipping (256 KiB).
        // Prevents a single large accidental commit from allocating fully in memory.
        const val MAX_SKILL_FILE_BYTES = 256 * 1024L

        // Max walk depth below the skills root.
        // Prevents runaway traversal of deeply nested directory trees.
        const val MAX_WALK_DEPTH = 10

        // Max number of SKILL.md files collected per catalog build.
        // Once reached, remaining files are counted but not parsed; a WARN is emitted.
        const val MAX_SKILL_COUNT = 500

        // Max characters for name and description fields injected into the LLM prompt.
        // Truncated values have a single ellipsis appended.
        const val MAX_SKILL_NAME_CHARS = 120
        const val MAX_SKILL_DESCRIPTION_CHARS = 500

        // Catalog TTL — one walk per namespace per minute under steady-state traffic.
        // Skill authors see edits reflected within 60 seconds.
        //
        // Cache eviction semantics: entries are NOT removed on TTL expiry. An expired entry
        // simply fails the freshness check and is overwritten by the next call on that same key.
        // If no further call arrives for a key (e.g. the namespace is deleted or its configPath
        // is no longer resolved), the entry and its List<Skill> remain in the ConcurrentHashMap
        // for the entire process lifetime. This is intentional: the map holds at most one entry
        // per distinct configPath resolved during the process lifetime, bounded by namespace count
        // rather than traffic volume. Worst case per entry is roughly 500 skills at ~600 bytes of
        // retained strings each — a few hundred KB at most — so the unbounded map is not a
        // concern in practice. A deleted namespace leaves a stale entry until process restart.
        //
        // No stampede guard: discoverSkills is called on every resolveAgentDefinition, i.e. every
        // agent invocation. Concurrent callers on a cold or just-expired key all pass the freshness
        // check and all run computeSkills, producing N redundant filesystem walks where one would
        // suffice. This is accepted because each walk is bounded by MAX_WALK_DEPTH and
        // MAX_SKILL_COUNT and runs on Dispatchers.IO. A per-key Mutex with a double-check inside
        // is the fix if this is ever measured to matter.
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SkillFrontmatterModel(
    val name: String? = null,
    val description: String? = null,
)
