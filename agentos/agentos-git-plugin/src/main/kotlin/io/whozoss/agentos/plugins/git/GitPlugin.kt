package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin

class GitPlugin : Plugin() {
    override fun start() {
        logger.info { "Git Plugin started!" }
    }

    override fun stop() {
        logger.info { "Git Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Tool provider for the GIT integration.
 *
 * Loading this plugin makes Git available on the instance: the service then offers the namespace
 * repository association. Worktrees stay under the service's control; this provider never offers
 * an agent a tool to create or remove one.
 */
@Extension
class GitToolProvider : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        logger.debug { "GIT integration '$configName': no tool outside a case Git workspace" }
        return emptyList()
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "GIT"

        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "Git Integration Configuration",
                "description": "Git tools for agents working in a case's Git workspace. Bind an auth setting holding each user's forge token: agents act with the identity of the user running the case.",
                "properties": {},
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
