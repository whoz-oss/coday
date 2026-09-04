package io.whozoss.agentos.skill

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Owns the grant decision for the built-in skill tools.
 *
 * Granted iff the agent's resolved skill catalogue is non-empty. No platform
 * default and no integrations key: [io.whozoss.agentos.agentConfig.AgentConfig.skillSelectors]
 * is already the explicit opt-in, so an agent with no selectors resolves no skills
 * and therefore receives no tools.
 *
 * Unlike the exchange and queryUser grants, this one builds the tools directly
 * instead of going through a [io.whozoss.agentos.sdk.tool.ToolPlugin]: the tools are
 * bound to the run's resolved skill list, which no plugin-registry lookup can supply.
 */
@Service
class SkillToolGrantService {
    /** Cheap, no I/O. */
    fun isGranted(skills: List<Skill>): Boolean = skills.isNotEmpty()

    /**
     * Builds the skill tools bound to [skills], so a read never rescans the filesystem
     * and can never reach a skill outside the agent's own catalogue.
     */
    fun grantTools(
        skills: List<Skill>,
        toolContext: ToolContext,
    ): List<StandardTool<*>> {
        logger.debug { "Granting built-in skill tools to agent '${toolContext.agentName}' (${skills.size} skills)" }
        return listOf(
            SkillReadTool(skills),
            SkillReadResourceTool(skills),
        )
    }

    companion object : KLogging()
}
