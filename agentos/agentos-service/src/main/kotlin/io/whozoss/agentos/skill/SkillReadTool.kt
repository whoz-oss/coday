package io.whozoss.agentos.skill

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.util.SensitiveFileDetector
import mu.KLogging

/**
 * Returns the body of a skill identified by its frontmatter [name].
 *
 * Skills are name-addressed so this tool works for both filesystem-backed
 * and DB-stored skills.
 */
class SkillReadTool(
    private val skills: List<Skill>,
) : StandardTool<SkillReadTool.Input> {
    data class Input(val name: String)

    override val name: String = "readSkill"
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        """
        Read the full instructions of a skill by its name.
        Call this when a task matches a skill from the catalog in your instructions.
        Returns the complete skill documentation including guidelines and workflows.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The exact skill name as listed in the skill catalog."
            }
          },
          "required": ["name"]
        }
        """.trimIndent()

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.name.isBlank()) {
            return ToolExecutionResult.error("A skill name is required.", errorType = "MISSING_INPUT")
        }
        val skill = skills.firstOrNull { it.name.equals(input.name, ignoreCase = true) }
            ?: return ToolExecutionResult.error(
                "Skill '${input.name}' not found. Available skills: ${skills.map { it.name }.joinToString(", ")}.",
                errorType = "NOT_FOUND",
            )
        return ToolExecutionResult.success(skill.body)
    }
}

/**
 * Reads a resource file bundled with a skill from [Skill.resources].
 *
 * Works symmetrically for both filesystem-backed and DB-persisted skills.
 */
class SkillReadResourceTool(
    private val skills: List<Skill>,
) : StandardTool<SkillReadResourceTool.Input> {
    data class Input(
        val name: String,
        val path: String,
    )

    override val name: String = "readSkillResource"
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        """
        Read a resource file bundled with a skill (e.g. a template or reference document).
        Provide the skill name and the relative path of the resource within the skill directory.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The exact skill name as listed in the skill catalog."
            },
            "path": {
              "type": "string",
              "description": "Relative path of the resource file within the skill directory."
            }
          },
          "required": ["name", "path"]
        }
        """.trimIndent()

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.name.isBlank()) {
            return ToolExecutionResult.error("A skill name is required.", errorType = "MISSING_INPUT")
        }
        if (input.path.isBlank()) {
            return ToolExecutionResult.error("A resource path is required.", errorType = "MISSING_INPUT")
        }

        val skill = skills.firstOrNull { it.name.equals(input.name, ignoreCase = true) }
            ?: return ToolExecutionResult.error(
                "Skill '${input.name}' not found.",
                errorType = "NOT_FOUND",
            )

        val normalizedPath = input.path.trim().trimStart('/').replace("\\", "/")

        if (SensitiveFileDetector.isSensitive(normalizedPath.substringAfterLast('/'))) {
            logger.warn { "[SkillReadResourceTool] Sensitive file rejected: $normalizedPath" }
            return ToolExecutionResult.error("Access denied: sensitive file.", errorType = "ACCESS_DENIED")
        }

        val content = skill.resources[normalizedPath]
            ?: return ToolExecutionResult.error(
                "Resource '${input.path}' not found in skill '${skill.name}'.",
                errorType = "NOT_FOUND",
            )

        return ToolExecutionResult.success(content)
    }

    companion object : KLogging() {
        const val MAX_RESOURCE_BYTES = 1 * 1024 * 1024L // 1 MiB

        fun isSensitiveFile(fileName: String): Boolean = SensitiveFileDetector.isSensitive(fileName)
    }
}
