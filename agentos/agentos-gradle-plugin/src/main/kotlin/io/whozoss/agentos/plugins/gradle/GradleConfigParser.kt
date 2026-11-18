package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Configuration of the GRADLE integration.
 *
 * @property projectDirectory Absolute path of the project (the repository or workspace root). Nothing outside it is
 *   ever run by the tools.
 * @property gradlewDirectory Directory that contains `gradlew`, relative to [projectDirectory] or absolute, and inside
 *   it. Null means [projectDirectory] itself.
 * @property javaHome Absolute path of the JDK Gradle runs on, passed as `JAVA_HOME`. Null leaves the environment of
 *   the AgentOS service as it is.
 * @property inheritEnvironment Names of the variables of the AgentOS service environment to pass to Gradle on top of
 *   the default set (see [GradleRunner.isInheritedVariable]). Empty by default: a build sees no secret of the service.
 */
data class GradleIntegrationConfig(
    val projectDirectory: String,
    val gradlewDirectory: String? = null,
    val javaHome: String? = null,
    val inheritEnvironment: Set<String> = emptySet(),
) {
    /** The directory that contains `gradlew`: [gradlewDirectory] resolved against [projectDirectory], else [projectDirectory]. */
    val gradleDirectory: File
        get() = GradleConfigParser.resolveGradlewDirectory(projectDirectory, gradlewDirectory)
}

/**
 * Parses and validates a [JsonNode] config into a [GradleIntegrationConfig].
 *
 * Validation rules:
 * - `projectDirectory` must be present, non-blank, and an absolute path.
 * - `gradlewDirectory` is optional (absent, JSON null and blank all mean unset); when set it is resolved against
 *   `projectDirectory` and the result must be inside it (no `..` that climbs out, no absolute path elsewhere).
 * - `javaHome` is optional (absent, JSON null and blank all mean unset); when set it must be an absolute path.
 * - `inheritEnvironment` is optional; when set it must be an array of valid environment variable names.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object GradleConfigParser {
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(config: JsonNode): GradleIntegrationConfig {
        val projectDirectory = config.get("projectDirectory")?.takeIf { !it.isNull }?.asText()?.trim()
        require(!projectDirectory.isNullOrBlank()) {
            "GRADLE integration config: 'projectDirectory' is required and must not be blank"
        }
        require(File(projectDirectory).isAbsolute) {
            "GRADLE integration config: 'projectDirectory' must be an absolute path, got: $projectDirectory"
        }
        val gradlewDirectory = config.get("gradlewDirectory")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
        if (gradlewDirectory != null) {
            val project = File(projectDirectory).toPath().normalize()
            require(resolveGradlewDirectory(projectDirectory, gradlewDirectory).toPath().startsWith(project)) {
                "GRADLE integration config: 'gradlewDirectory' must be inside 'projectDirectory' ($projectDirectory), " +
                    "got: $gradlewDirectory"
            }
        }
        val javaHome = config.get("javaHome")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
        require(javaHome == null || File(javaHome).isAbsolute) {
            "GRADLE integration config: 'javaHome' must be an absolute path, got: $javaHome"
        }
        return GradleIntegrationConfig(
            projectDirectory = projectDirectory,
            gradlewDirectory = gradlewDirectory,
            javaHome = javaHome,
            inheritEnvironment = parseInheritEnvironment(config.get("inheritEnvironment")),
        )
    }

    /** [gradlewDirectory] resolved against [projectDirectory] and normalized, or [projectDirectory] itself when null. */
    internal fun resolveGradlewDirectory(
        projectDirectory: String,
        gradlewDirectory: String?,
    ): File {
        val project = File(projectDirectory).toPath().normalize()
        if (gradlewDirectory.isNullOrBlank()) return project.toFile()
        val given = File(gradlewDirectory.trim())
        return (if (given.isAbsolute) given.toPath() else project.resolve(gradlewDirectory.trim())).normalize().toFile()
    }

    private fun parseInheritEnvironment(node: JsonNode?): Set<String> {
        val array = node?.takeIf { !it.isNull }
        require(array == null || array.isArray) {
            "GRADLE integration config: 'inheritEnvironment' must be an array of variable names"
        }
        val names = array?.map { it.asText().trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        names.forEach { name ->
            require(ENV_NAME.matches(name)) {
                "GRADLE integration config: 'inheritEnvironment' has an invalid variable name: '$name'"
            }
        }
        return names
    }
}
