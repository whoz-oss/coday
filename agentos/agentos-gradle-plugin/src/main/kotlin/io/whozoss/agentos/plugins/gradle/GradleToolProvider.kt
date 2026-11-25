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
 * Exposes three tools sharing one [GradleRunner] on the configured project: [GradleTestTool] (`gradle_tests`),
 * [GradleCompileTool] (`gradle_compile`) and [GradleDependencyInsightTool] (`gradle_dependency_insight`). The build
 * runs through the `gradlew` of the configured `gradlewDirectory`, inside the `projectDirectory`.
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

        val runner =
            GradleRunner(
                projectDir = File(integrationConfig.projectDirectory),
                javaHome = integrationConfig.javaHome,
                inheritedEnvironment = integrationConfig.inheritEnvironment,
                gradlewDirectory = integrationConfig.gradleDirectory,
            )
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
                    "description": "Exposes Gradle build operations (compile, run tests, dependency insight) as tools for the LLM, run through the gradlew of the configured project. The tools run the build of the repository with the rights of the AgentOS service: only give them to agents you trust with that. For a repository with several Gradle builds, declare one GRADLE integration per build.",
                    "properties": {
                        "projectDirectory": {
                            "type": "string",
                            "title": "Project Directory",
                            "description": "Absolute path of the project (the repository or workspace root). Nothing outside it is ever run. Write {{CASE_EXCHANGE}} to use the current case's own directory; in a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. In a filesystem integration config, {{NAMESPACE_CONFIG_PATH}} resolves to the namespace config path (e.g. {{NAMESPACE_CONFIG_PATH}}/../..)."
                        },
                        "gradlewDirectory": {
                            "type": "string",
                            "title": "Gradlew Directory",
                            "description": "Directory that contains gradlew (the directory, not the gradlew file), relative to the project directory (e.g. 'backend') or absolute, and inside it. The Gradle build starts there and the gradlew is looked for there only. Leave empty when gradlew is at the root of the project directory."
                        },
                        "javaHome": {
                            "type": "string",
                            "title": "Java Home",
                            "description": "Optional absolute path of the JDK Gradle runs on, passed as JAVA_HOME. Leave empty to use the JAVA_HOME, or else the java found on the PATH, of the AgentOS service."
                        },
                        "inheritEnvironment": {
                            "type": "array",
                            "title": "Inherited Environment Variables",
                            "description": "Names of environment variables of the AgentOS service to pass to Gradle on top of the default set. Gradle runs with a minimal environment (PATH, HOME, JAVA_HOME, locale, proxies, certificates, GRADLE_USER_HOME, GRADLE_OPTS) so that the secrets of the service (LLM keys, tokens) are not visible to the build nor to its tests: list here the ones a build really needs, e.g. ORG_GRADLE_PROJECT_githubToken.",
                            "items": { "type": "string" }
                        }
                    },
                    "required": ["projectDirectory"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
