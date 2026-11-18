package io.whozoss.agentos.plugins.gradle

/**
 * One module to test, with its own filters. Received from the agent through the `targets` tool parameter.
 *
 * @property subproject Gradle subproject name, e.g. `cli` or `:server`. Required.
 * @property testFilter filters for this module only, same syntax as the tool's `testFilter`. Null runs all its tests.
 */
data class TestTarget(
    val subproject: String,
    val testFilter: List<String>? = null,
)

/**
 * One Gradle `test` task to run, resolved from either the legacy params or [TestTarget]s.
 *
 * @property subproject null runs the plain `test` task (all modules).
 * @property filters raw filters, blank-free, in the Gradle `--tests` syntax.
 */
internal data class TestTask(
    val subproject: String?,
    val filters: List<String> = emptyList(),
)
