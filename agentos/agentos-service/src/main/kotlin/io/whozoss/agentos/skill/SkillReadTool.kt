package io.whozoss.agentos.skill

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Returns the body of a skill identified by its frontmatter [name].
 *
 * Skills are name-addressed so this tool works for both filesystem-backed
 * and future DB-stored skills.
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
 * Reads a file adjacent to a skill, resolved under [Skill.resourceRoot].
 *
 * Enforces path containment (no traversal) and rejects sensitive filenames.
 * Returns an error when [Skill.resourceRoot] is null (DB-stored skills have no
 * bundled resources in step 1).
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

        val resourceRoot = skill.resourceRoot
            ?: return ToolExecutionResult.error(
                "Skill '${skill.name}' has no bundled resources (not filesystem-backed).",
                errorType = "NO_RESOURCE_ROOT",
            )

        val rootPath =
            try {
                Path.of(resourceRoot).toRealPath()
            } catch (e: NoSuchFileException) {
                return ToolExecutionResult.error("Skill resource directory not found.", errorType = "NOT_FOUND")
            } catch (e: IOException) {
                logger.warn(e) { "[SkillReadResourceTool] Cannot resolve resource root for skill '${skill.name}'" }
                return ToolExecutionResult.error("Could not access skill resource directory.", errorType = "IO_ERROR")
            }

        val resolved =
            try {
                rootPath.resolve(input.path).toRealPath()
            } catch (e: NoSuchFileException) {
                return ToolExecutionResult.error("Resource '${input.path}' not found in skill '${skill.name}'.", errorType = "NOT_FOUND")
            } catch (e: IOException) {
                return ToolExecutionResult.error("Could not resolve resource path '${input.path}'.", errorType = "IO_ERROR")
            }

        // Path containment: reject symlink escapes.
        if (!resolved.startsWith(rootPath)) {
            logger.warn { "[SkillReadResourceTool] Path traversal attempt for skill '${skill.name}': ${input.path}" }
            return ToolExecutionResult.error("Access denied: resource path escapes the skill directory.", errorType = "ACCESS_DENIED")
        }

        // Sensitive-file deny-list.
        if (isSensitiveFile(resolved.fileName.toString())) {
            logger.warn { "[SkillReadResourceTool] Sensitive file rejected: $resolved" }
            return ToolExecutionResult.error("Access denied: sensitive file.", errorType = "ACCESS_DENIED")
        }

        if (!Files.isRegularFile(resolved)) {
            return ToolExecutionResult.error("'${input.path}' is not a regular file.", errorType = "NOT_A_FILE")
        }

        val size = Files.size(resolved)
        if (size > MAX_RESOURCE_BYTES) {
            return ToolExecutionResult.error(
                "Resource '${input.path}' is too large (${size}B > ${MAX_RESOURCE_BYTES}B).",
                errorType = "TOO_LARGE",
            )
        }

        return try {
            ToolExecutionResult.success(Files.readString(resolved))
        } catch (e: IOException) {
            logger.warn(e) { "[SkillReadResourceTool] Could not read $resolved" }
            ToolExecutionResult.error("Could not read resource '${input.path}'.", errorType = "IO_ERROR")
        }
    }

    companion object : KLogging() {
        // Reuse the same deny-list as AgentDocumentResolver.
        private val SENSITIVE_FILE_PATTERNS =
            listOf(
                ".env",
                ".env.*",
                "credentials.json",
                "*.key",
                "*.pem",
                "token.json",
                "auth-profiles.json",
                "*.p12",
                "*.pfx",
                "id_rsa",
                "id_dsa",
                "id_ecdsa",
                "id_ed25519",
            )

        const val MAX_RESOURCE_BYTES = 1 * 1024 * 1024L // 1 MiB

        fun isSensitiveFile(fileName: String): Boolean =
            SENSITIVE_FILE_PATTERNS.any { pattern -> matchesGlob(fileName, pattern) }

        private fun matchesGlob(
            name: String,
            pattern: String,
        ): Boolean {
            val regex =
                Regex(
                    pattern.split("*").joinToString(".*") { Regex.escape(it) },
                    RegexOption.IGNORE_CASE,
                )
            return regex.matches(name)
        }
    }
}
