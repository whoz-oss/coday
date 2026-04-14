package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.tool.StandardTool
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Builds a structured text block describing tool permissions for the current user.
 *
 * The manifest is injected into the agent's system prompt so the LLM knows which
 * tools it may call and which are forbidden. This is the guidance layer of the
 * defense-in-depth model — the backend [ToolExecutionGuard] remains the enforcement
 * layer regardless of what the LLM decides.
 *
 * **Privacy (NFR6):** Only tool names and categories are included in the manifest.
 * User IDs, internal roles, and permission levels are never exposed to the LLM.
 */
@Service
class PermissionManifestBuilder(
    private val authorizationService: AuthorizationService,
) {
    companion object : KLogging()

    /**
     * Generate the permission manifest for injection into system instructions.
     *
     * @param userId  current user identity (String, not UUID)
     * @param caseId  current case identity (String, not UUID)
     * @param allTools complete collection of tools resolved for the namespace
     * @return a structured text block, or empty string when [allTools] is empty
     */
    fun buildManifest(
        userId: String,
        caseId: String,
        allTools: Collection<StandardTool<*>>,
    ): String {
        if (allTools.isEmpty()) return ""

        val toolCategoryMap = allTools.associate { it.name to it.category }
        val allowedToolNames = authorizationService.getAvailableTools(userId, caseId, toolCategoryMap)

        val allowed = allTools.filter { it.name in allowedToolNames }
        val forbidden = allTools.filter { it.name !in allowedToolNames }

        return buildString {
            appendLine("## Tool Permissions")
            appendLine("You MUST respect these permissions. Never attempt to call a forbidden tool.")
            appendLine()
            appendLine("### Allowed tools:")
            allowed.forEach { tool ->
                appendLine("- ${tool.name} (${tool.category.name})")
            }
            if (forbidden.isNotEmpty()) {
                appendLine()
                appendLine("### Forbidden tools (do NOT call these):")
                forbidden.forEach { tool ->
                    appendLine("- ${tool.name} (${tool.category.name}) — Requires ${tool.category.minimumNamespaceRole.name} role")
                }
            }
        }.trim()
    }
}
