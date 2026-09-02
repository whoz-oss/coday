package io.whozoss.agentos.skill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KLogging
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

data class Skill(
    val name: String,
    val description: String,
    val relativePath: String,
    val skillRelativePath: String,
)

@Component
class SkillResolver {

    private val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    fun discoverSkills(namespaceConfigPath: String?): List<Skill> {
        if (namespaceConfigPath.isNullOrBlank()) return emptyList()

        val configPath = Path.of(namespaceConfigPath)
        val skillsRoot = configPath.resolve(SKILLS_SUBDIR)

        if (!Files.exists(skillsRoot) || !Files.isDirectory(skillsRoot)) {
            logger.debug { "[SkillResolver] Skills directory does not exist or is not a directory: $skillsRoot" }
            return emptyList()
        }

        val canonicalSkillsRoot =
            try {
                skillsRoot.toRealPath()
            } catch (e: Exception) {
                logger.warn(e) { "[SkillResolver] Could not resolve canonical path for skills root: $skillsRoot" }
                return emptyList()
            }

        val skillFiles = mutableListOf<Path>()

        try {
            Files.walk(skillsRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.name == SKILL_FILE_NAME }
                    .forEach { file ->
                        if (isContainedInRoot(file, canonicalSkillsRoot)) {
                            skillFiles.add(file)
                        } else {
                            logger.warn { "[SkillResolver] Skipping $file: escapes canonical boundary of $canonicalSkillsRoot" }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[SkillResolver] Error walking skills directory: $skillsRoot" }
            return emptyList()
        }

        val projectRoot = configPath.parent ?: configPath
        val sortedFiles = skillFiles.sortedBy { projectRoot.relativize(it).pathString.replace('\\', '/') }

        val discoveredSkills = mutableListOf<Skill>()
        val seenNames = mutableSetOf<String>()

        for (file in sortedFiles) {
            val relativePath = projectRoot.relativize(file).pathString.replace('\\', '/')
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

        return discoveredSkills
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

    fun buildSkillsBlock(
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
            return skill.skillRelativePath.equals(prefix, ignoreCase = true) ||
                skill.skillRelativePath.startsWith("$prefix/", ignoreCase = true)
        }

        if (skill.skillRelativePath.equals(normalizedSelector, ignoreCase = true)) {
            return true
        }

        if ("${skill.skillRelativePath}/$SKILL_FILE_NAME".equals(normalizedSelector, ignoreCase = true)) {
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

        return Skill(
            name = model.name.trim(),
            description = model.description.trim(),
            relativePath = relativePath,
            skillRelativePath = skillRelativePath,
        )
    }

    private fun extractFrontmatter(content: String): String? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null

        val lines = trimmed.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") return null

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex == -1) return null

        return lines.subList(1, endIndex + 1).joinToString("\n")
    }

    private fun isContainedInRoot(file: Path, canonicalRoot: Path): Boolean {
        return try {
            val canonicalFile = file.toRealPath()
            canonicalFile.startsWith(canonicalRoot)
        } catch (e: NoSuchFileException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    companion object : KLogging() {
        const val SKILLS_SUBDIR = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SkillFrontmatterModel(
    val name: String? = null,
    val description: String? = null,
)
