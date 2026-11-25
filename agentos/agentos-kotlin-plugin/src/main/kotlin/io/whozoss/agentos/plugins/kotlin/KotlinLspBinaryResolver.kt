package io.whozoss.agentos.plugins.kotlin

import java.nio.file.Files
import java.nio.file.Path

/**
 * Environment variable naming the Kotlin LSP server binary, so it can live outside the project: it weighs over 1 GB.
 *
 * The binary is deliberately not an integration parameter: parameters can be overlaid by a user scope, and the
 * binary is executed by the service. It is chosen by whoever operates the service.
 */
internal const val ENV_SERVER_BINARY = "AGENTOS_KOTLIN_LSP_BINARY"

private const val BINARY_RELATIVE = "server/bin/intellij-server"

/**
 * Finds the Kotlin LSP server binary, first match wins:
 * 1. the environment variable [ENV_SERVER_BINARY];
 * 2. the user install `~/kotlin-lsp`.
 *
 * No location lies inside the project: an agent able to write there could otherwise have the service run its file.
 * Location 1 is explicit: when set but unusable, resolution fails rather than silently using another binary.
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
     * @throws IllegalStateException when the explicit location is unusable, or when nothing is found
     */
    fun resolve(): Path {
        val fromEnvironment = env(ENV_SERVER_BINARY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val userInstall = Path.of(userHome, "kotlin-lsp", BINARY_RELATIVE)
        return when {
            fromEnvironment != null -> {
                check(isExecutable(fromEnvironment)) {
                    "Kotlin LSP binary from environment variable $ENV_SERVER_BINARY is missing or not executable: $fromEnvironment"
                }
                fromEnvironment
            }

            isExecutable(userInstall) -> {
                userInstall
            }

            else -> {
                error(
                    "Kotlin LSP binary not found. Tried: $userInstall. " +
                        "Set the environment variable $ENV_SERVER_BINARY of the AgentOS service, or install it at $userInstall.",
                )
            }
        }
    }
}
