package io.whozoss.factory.verification.domain

import java.nio.file.Path

/** A single named oracle inside a domain. */
data class OracleSpec(
    val name: String,
    val command: String,
    val cwd: Path,
    val filesArg: Boolean = false,
    val buildHostArg: Boolean = false,
)

/** A build domain: a uniform list of named oracles. */
data class Domain(
    val oracles: List<OracleSpec>,
)

/**
 * Oracle commands per domain — ported from `factory/lib/domains.mjs`.
 *
 * These commands are written in hard, versioned in the repository. No agent chooses
 * them, runs them, or reports their result: they are part of the orchestrator
 * contract.
 *
 * The resolution is a pure function of the environment map and a default repo root;
 * environment overrides are honoured:
 *   - `FACTORY_ROOT`                    → repository root (overrides the default).
 *   - `FACTORY_COMMAND_BACK`            → back `build` oracle command.
 *   - `FACTORY_CWD_BACK`                → back oracle working directory (default `<root>/agentos`).
 *   - `FACTORY_COMMAND_FRONT_BUILD`     → front `build` oracle command.
 *   - `FACTORY_COMMAND_FRONT`           → front `tests` oracle command (historical override).
 *   - `FACTORY_CWD_FRONT`               → front oracles working directory (default `<root>`).
 *   - `FACTORY_FRONT_TEST_TARGET`       → default front test target (default `frontend-test`).
 */
object DomainResolver {

    const val DEFAULT_BACK_COMMAND: String =
        "./gradlew :agentos-service:build --rerun-tasks --console=plain"

    const val DEFAULT_FRONT_BUILD_COMMAND: String =
        "pnpm nx run-many --target=build --configuration=development --skip-nx-cache"

    const val DEFAULT_FRONT_TEST_TARGET: String = "frontend-test"

    const val ENV_ROOT = "FACTORY_ROOT"
    const val ENV_COMMAND_BACK = "FACTORY_COMMAND_BACK"
    const val ENV_CWD_BACK = "FACTORY_CWD_BACK"
    const val ENV_COMMAND_FRONT_BUILD = "FACTORY_COMMAND_FRONT_BUILD"
    const val ENV_COMMAND_FRONT = "FACTORY_COMMAND_FRONT"
    const val ENV_CWD_FRONT = "FACTORY_CWD_FRONT"
    const val ENV_FRONT_TEST_TARGET = "FACTORY_FRONT_TEST_TARGET"

    /**
     * Resolves the target repository root: `FACTORY_ROOT` when set (resolved
     * absolute), otherwise [defaultRepoRoot].
     */
    fun resolveRepoRoot(environment: Map<String, String>, defaultRepoRoot: Path): Path {
        val override = environment[ENV_ROOT]
        return if (!override.isNullOrEmpty()) {
            Path.of(override).toAbsolutePath().normalize()
        } else {
            defaultRepoRoot.toAbsolutePath().normalize()
        }
    }

    /** The `back` domain: one Gradle build oracle. */
    fun back(environment: Map<String, String>, defaultRepoRoot: Path): Domain {
        val repoRoot = resolveRepoRoot(environment, defaultRepoRoot)
        val command = environment[ENV_COMMAND_BACK] ?: DEFAULT_BACK_COMMAND
        val cwd = environment[ENV_CWD_BACK]?.let { Path.of(it) } ?: repoRoot.resolve("agentos")
        return Domain(
            oracles = listOf(
                OracleSpec(name = "build", command = command, cwd = cwd),
            ),
        )
    }

    /** The `front` domain: an Angular build oracle then a behaviour (tests) oracle. */
    fun front(environment: Map<String, String>, defaultRepoRoot: Path): Domain {
        val repoRoot = resolveRepoRoot(environment, defaultRepoRoot)
        val cwd = environment[ENV_CWD_FRONT]?.let { Path.of(it) } ?: repoRoot
        val testTarget = environment[ENV_FRONT_TEST_TARGET] ?: DEFAULT_FRONT_TEST_TARGET

        return Domain(
            oracles = listOf(
                OracleSpec(
                    name = "build",
                    command = environment[ENV_COMMAND_FRONT_BUILD] ?: DEFAULT_FRONT_BUILD_COMMAND,
                    cwd = cwd,
                    buildHostArg = true,
                ),
                OracleSpec(
                    name = "tests",
                    command = environment[ENV_COMMAND_FRONT] ?: "pnpm nx affected -t $testTarget",
                    cwd = cwd,
                    filesArg = true,
                ),
            ),
        )
    }

    /** All domains, keyed by name (`back`, `front`). */
    fun resolve(environment: Map<String, String>, defaultRepoRoot: Path): Map<String, Domain> =
        linkedMapOf(
            "back" to back(environment, defaultRepoRoot),
            "front" to front(environment, defaultRepoRoot),
        )
}
