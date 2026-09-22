package io.whozoss.agentos.skill

/**
 * Renders the skill catalog block injected into agent instructions.
 *
 * The catalog lists skill names and descriptions only — no filesystem paths.
 * The agent calls [SkillReadTool] by name to retrieve a skill's body.
 */
object SkillCatalogRenderer {
    /**
     * Builds the catalog block for [skills]. Returns null when [skills] is empty.
     */
    fun buildBlock(skills: List<Skill>): String? {
        if (skills.isEmpty()) return null
        return buildString {
            appendLine()
            appendLine("## Available Skills")
            appendLine()
            appendLine(
                "You have access to the following domain skills. " +
                    "When a task matches a skill's description, call readSkill(\"<name>\") " +
                    "to retrieve its instructions and follow its guidelines on demand.",
            )
            appendLine()
            skills.forEach { skill ->
                appendLine("- **${skill.name}**: ${skill.description}")
            }
            appendLine()
            appendLine("### Skill Activation Protocol")
            appendLine("1. Identify relevant skills from the catalog above based on the task description.")
            appendLine("2. Call readSkill(\"<name>\") before executing skill-specific workflows.")
            appendLine("3. Follow instructions, scripts, or references defined in the skill documentation on-demand.")
        }.trimEnd()
    }
}
