package io.whozoss.agentos.plugins.kotlin

import java.nio.file.Files
import java.nio.file.Path

/**
 * Environment variable naming the Kotlin LSP server binary when the integration does not, so that it can live outside
 * the project: the binary weighs over 1 GB.
 */
internal const val ENV_SERVER_BINARY = "AGENTOS_KOTLIN_LSP_BINARY"

private const val BINARY_RELATIVE = "server/bin/intellij-server"

/**
 * Finds the Kotlin LSP server binary, first match wins:
 * 1. the `serverBinary` parameter of the integration;
 * 2. the environment variable [ENV_SERVER_BINARY] of the service;
 * 3. the user install `~/kotlin-lsp`.
 *
 * The service executes the binary with its own rights, so it is chosen by whoever configures the integration, which
 * only an administrator can do (`KOTLIN` is refused in user scopes). No default location lies inside the project: an
 * agent able to write there could otherwise have the service run its file (an explicit one is refused by
 * [KotlinToolProvider]).
 *
 * Locations 1 and 2 are explicit: when set but unusable, resolution fails rather than silently using another binary.
 * Depends on nothing but its inputs (no class location, no real file system), so it is testable as is.
 */
internal class KotlinLspBinaryResolver(
    private val env: (String) -> String? = System::getenv,
    private val userHome: String = System.getProperty("user.home"),
    private val isExecutable: (Path) -> Boolean = Files::isExecutable,
) {
    /**
     * The binary to start.
     *
     * @param configured the `serverBinary` parameter of the integration, null or blank when it sets none
     * @throws IllegalStateException when an explicit location is unusable, or when nothing is found
     */
    fun resolve(configured: String? = null): Path {
        val fromIntegration = configured?.takeIf { it.isNotBlank() }?.let { Path.of(it.trim()) }
        val fromEnvironment = env(ENV_SERVER_BINARY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val userInstall = Path.of(userHome, "kotlin-lsp", BINARY_RELATIVE)
        return when {
            fromIntegration != null -> {
                usable(path = fromIntegration, origin = "the integration parameter '${KotlinConfigParser.SERVER_BINARY}'")
            }

            fromEnvironment != null -> {
                usable(path = fromEnvironment, origin = "environment variable $ENV_SERVER_BINARY")
            }

            isExecutable(userInstall) -> {
                userInstall
            }

            else -> {
                error(
                    "Kotlin LSP binary not found. Tried: $userInstall. " +
                        "Set the '${KotlinConfigParser.SERVER_BINARY}' parameter of the integration or the environment variable " +
                        "$ENV_SERVER_BINARY of the AgentOS service, or install it at $userInstall.",
                )
            }
        }
    }

    private fun usable(
        path: Path,
        origin: String,
    ): Path =
        path.also {
            check(isExecutable(it)) { "Kotlin LSP binary from $origin is missing or not executable: $it" }
        }
}
