package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import java.io.File

/**
 * Tool provider for the GRADLE integration.
 *
 * Exposes three tools sharing one [GradleRunner] on the configured `projectDirectory`:
 * [GradleTestTool] (`gradle_tests`), [GradleCompileTool] (`gradle_compile`) and
 * [GradleDependencyInsightTool] (`gradle_dependency_insight`).
 *
 * Config is validated by [GradleConfigParser] on every [provideTools] call. A missing or invalid
 * config logs and returns an empty list rather than crashing the service.
 */
@Extension
class GradleToolProvider : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        if (config == null || config.isNull) {
            logger.warn { "GRADLE integration '$configName': no config provided, no tools registered" }
            return emptyList()
        }

        val integrationConfig =
            try {
                GradleConfigParser.parse(config)
            } catch (e: IllegalArgumentException) {
                logger.error { "GRADLE integration '$configName': invalid config — ${e.message}" }
                return emptyList()
            }

        val runner = GradleRunner(File(integrationConfig.projectDirectory))
        return listOf(
            GradleTestTool(runner, configName = configName),
            GradleCompileTool(runner, configName),
            GradleDependencyInsightTool(runner, configName),
        )
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "GRADLE"

        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "Gradle Integration Configuration",
                    "description": "Exposes Gradle build operations (compile, run tests, dependency insight) as tools for the LLM, run through the gradlew of the configured project.",
                    "properties": {
                        "projectDirectory": {
                            "type": "string",
                            "title": "Project Directory",
                            "description": "Absolute path of the Gradle project the tools run in. Write {{CASE_EXCHANGE}} to use the current case's own directory; in a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. In a filesystem integration config, {{NAMESPACE_CONFIG_PATH}} resolves to the namespace config path (e.g. {{NAMESPACE_CONFIG_PATH}}/../..)."
                        }
                    },
                    "required": ["projectDirectory"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
