package io.whozoss.agentos.skill

import io.whozoss.agentos.namespace.NamespaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SkillServiceImpl(
    private val filesystemSkillRepository: FilesystemSkillRepository,
    private val namespaceService: NamespaceService,
) : SkillService {

    override suspend fun findSkills(
        namespaceId: UUID,
        selectors: List<String>?,
    ): List<Skill> {
        if (selectors.isNullOrEmpty()) return emptyList()
        val allSkills = loadAllSkills(namespaceId)
        return filterSkills(allSkills, selectors)
    }

    override suspend fun findSkillByName(
        namespaceId: UUID,
        name: String,
    ): Skill? {
        val allSkills = loadAllSkills(namespaceId)
        return allSkills.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private suspend fun loadAllSkills(namespaceId: UUID): List<Skill> {
        val namespace = withContext(Dispatchers.IO) {
            namespaceService.findById(namespaceId)
        } ?: return emptyList()
        val configPath = namespace.configPath ?: return emptyList()
        return withContext(Dispatchers.IO) {
            filesystemSkillRepository.findAll(configPath)
        }
    }

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
                            val path = skill.skillRelativePath ?: ""
                            path == prefix || path.startsWith("$prefix/")
                        }
                    }
                }
                normalized.endsWith("/*") -> {
                    val prefix = normalized.removeSuffix("/*").trimStart('/')
                    if (prefix.isNotEmpty()) {
                        skills.filterTo(matched) { skill ->
                            val path = skill.skillRelativePath ?: ""
                            path == prefix || path.startsWith("$prefix/")
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

    companion object : KLogging()
}
