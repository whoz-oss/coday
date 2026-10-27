package io.whozoss.factory.verification.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Effective oracle command construction — ported from
 * `factory/src/application/oracle/oracle-command.ts`.
 *
 * This module touches the filesystem (reads `project.json`) and the environment
 * (owner/build-host resolution) but never the network. It is deliberately separate
 * from the pure oracle domain.
 *
 * A [OracleCommandResult.NoHost] must be treated by workflows as an
 * ORACLE_INFRASTRUCTURE signal (human gate), never as an empty success.
 */
object OracleCommand {

    private val MAPPER = ObjectMapper()

    /** An oracle definition as consumed by [buildOracleCommand]. */
    data class OracleCommandSpec(
        val command: String,
        val filesArg: Boolean = false,
        val buildHostArg: Boolean = false,
    )

    /** Result of resolving buildable hosts. */
    sealed interface BuildHostResult {
        data class Hosts(val hosts: List<String>) : BuildHostResult
        data class NoHost(val reason: String, val ownerProjects: List<String>) : BuildHostResult
    }

    /** The command to hand to `runCommand`, or a no-host sentinel. */
    sealed interface OracleCommandResult {
        data class Command(val command: String) : OracleCommandResult
        data class NoHost(val reason: String, val ownerProjects: List<String>) : OracleCommandResult
    }

    /**
     * Resolves the Nx owner projects of a list of files.
     *
     * Files without a `project.json` in their ancestry are silently ignored: they
     * belong to no known Nx project.
     */
    fun resolveOwnerProjects(files: List<String>, repoRoot: Path): List<String> {
        val seen = LinkedHashSet<String>()
        val projects = mutableListOf<String>()

        for (file in files) {
            val absoluteFile = repoRoot.resolve(file).normalize()
            var dir: Path? = absoluteFile.parent

            while (dir != null && dir.startsWith(repoRoot)) {
                val candidate = dir.resolve("project.json")
                if (Files.exists(candidate)) {
                    try {
                        val json = MAPPER.readTree(candidate.toFile())
                        val name = json.get("name")?.takeIf { it.isTextual }?.asText()
                        if (name != null && seen.add(name)) projects.add(name)
                    } catch (_: Exception) {
                        // Malformed JSON: stop the walk for this file.
                    }
                    break
                }
                val parent = dir.parent
                if (parent == dir) break
                dir = parent
            }
        }

        return projects
    }

    /**
     * Resolves buildable host projects from owner projects via the JSON
     * `FACTORY_FRONT_BUILD_HOST_MAP` environment variable:
     * `{ "<owner>": ["<host-app>", ...], "*": ["<fallback-host>", ...] }`.
     */
    fun resolveBuildHosts(
        ownerProjects: List<String>,
        repoRoot: Path,
        environment: Map<String, String> = System.getenv(),
    ): BuildHostResult {
        val mapRaw = environment["FACTORY_FRONT_BUILD_HOST_MAP"]
            ?: return BuildHostResult.NoHost(
                reason = "FACTORY_FRONT_BUILD_HOST_MAP is not set. " +
                    "Cannot resolve buildable host applications for owner projects: " +
                    ownerProjects.joinToString(", ") + ". " +
                    "Set this env var to a JSON map of owner project → host app(s). " +
                    "Example: '{\"*\":[\"aphrodite\",\"admin\",\"agentic-studio\",\"copilot-chat\"]}'.",
                ownerProjects = ownerProjects,
            )

        val hostMap: JsonNode = try {
            MAPPER.readTree(mapRaw)
        } catch (e: Exception) {
            return BuildHostResult.NoHost(
                reason = "FACTORY_FRONT_BUILD_HOST_MAP is not valid JSON: ${e.message}. Raw value: ${mapRaw.take(200)}",
                ownerProjects = ownerProjects,
            )
        }

        if (!hostMap.isObject) {
            return BuildHostResult.NoHost(
                reason = "FACTORY_FRONT_BUILD_HOST_MAP must be a JSON object, got: ${hostMap.nodeType}",
                ownerProjects = ownerProjects,
            )
        }

        fun stringList(node: JsonNode?): List<String> =
            if (node != null && node.isArray) node.filter { it.isTextual }.map { it.asText() } else emptyList()

        val fallbackHosts = stringList(hostMap.get("*"))

        val seen = LinkedHashSet<String>()
        val hosts = mutableListOf<String>()
        for (owner in ownerProjects) {
            val mapped = hostMap.get(owner)?.takeIf { it.isArray }?.let { stringList(it) } ?: fallbackHosts
            for (host in mapped) {
                if (seen.add(host)) hosts.add(host)
            }
        }

        if (hosts.isEmpty()) {
            return BuildHostResult.NoHost(
                reason = "No buildable host found for owner projects: " + ownerProjects.joinToString(", ") + ". " +
                    "The host map has no entry for these projects and no fallback (\"*\") is defined. " +
                    "Add entries to FACTORY_FRONT_BUILD_HOST_MAP.",
                ownerProjects = ownerProjects,
            )
        }

        // Verify each host actually has a `build` target in its project.json.
        // A host without a build target is excluded — never silently accepted.
        val validHosts = mutableListOf<String>()
        val invalidHosts = mutableListOf<String>()

        for (host in hosts) {
            val candidatePaths = listOf(
                repoRoot.resolve("apps").resolve(host).resolve("project.json"),
                repoRoot.resolve("frontend").resolve("apps").resolve(host).resolve("project.json"),
                repoRoot.resolve(host).resolve("project.json"),
            )

            var hasBuildTarget = false
            var found = false
            for (candidate in candidatePaths) {
                if (Files.exists(candidate)) {
                    found = true
                    try {
                        val json = MAPPER.readTree(candidate.toFile())
                        val targets = json.get("targets")
                        if (targets != null && (targets.has("build") || targets.has("build-angular"))) {
                            hasBuildTarget = true
                        }
                    } catch (_: Exception) {
                        // Malformed project.json: considered without a build target.
                    }
                    break
                }
            }

            when {
                !found -> validHosts.add(host) // Accepted tentatively, as in the Node instrument.
                hasBuildTarget -> validHosts.add(host)
                else -> invalidHosts.add(host)
            }
        }

        if (validHosts.isEmpty()) {
            return BuildHostResult.NoHost(
                reason = "All resolved hosts (" + hosts.joinToString(", ") + ") lack a `build` or `build-angular` " +
                    "target in their project.json. Owner projects: " + ownerProjects.joinToString(", ") + ". " +
                    "Excluded hosts: " + invalidHosts.joinToString(", ") + ". " +
                    "Update FACTORY_FRONT_BUILD_HOST_MAP to reference apps with real build targets.",
                ownerProjects = ownerProjects,
            )
        }

        return BuildHostResult.Hosts(validHosts)
    }

    /**
     * Builds the effective command handed to `runCommand` for a given oracle.
     *
     * Four cases: fixed scope (command unchanged), `buildHostArg` (host resolution),
     * `filesArg` with an empty list (command unchanged) and `filesArg` with files
     * (`pnpm nx run-many --target=<target> --projects=... --skip-nx-cache`).
     */
    fun buildOracleCommand(
        oracle: OracleCommandSpec,
        files: List<String>,
        repoRoot: Path,
        environment: Map<String, String> = System.getenv(),
    ): OracleCommandResult {
        // Case 2: `buildHostArg` — buildable host resolution.
        if (oracle.buildHostArg) {
            val ownerProjects = if (files.isNotEmpty()) resolveOwnerProjects(files, repoRoot) else emptyList()

            if (ownerProjects.isEmpty() && files.isNotEmpty()) {
                return OracleCommandResult.NoHost(
                    reason = "No Nx owner project found for modified files: " + files.joinToString(", ") + ". " +
                        "Modified files may be in root-level directories without a project.json.",
                    ownerProjects = emptyList(),
                )
            }
            if (ownerProjects.isEmpty()) {
                return OracleCommandResult.NoHost(
                    reason = "No files provided to build oracle. Cannot resolve build host applications.",
                    ownerProjects = emptyList(),
                )
            }

            when (val hostsResult = resolveBuildHosts(ownerProjects, repoRoot, environment)) {
                is BuildHostResult.NoHost ->
                    return OracleCommandResult.NoHost(hostsResult.reason, hostsResult.ownerProjects)
                is BuildHostResult.Hosts -> {
                    if (oracle.command.contains("--projects=")) return OracleCommandResult.Command(oracle.command)
                    return OracleCommandResult.Command(oracle.command + " --projects=" + hostsResult.hosts.joinToString(","))
                }
            }
        }

        // Case 1: neither `filesArg` nor `buildHostArg` — fixed scope.
        if (!oracle.filesArg) return OracleCommandResult.Command(oracle.command)

        // Case 3: `filesArg: true` but empty list.
        if (files.isEmpty()) return OracleCommandResult.Command(oracle.command)

        // Case 4: `filesArg: true` with files — `run-many --projects` strategy.
        val target = extractTarget(oracle.command) ?: return OracleCommandResult.Command(oracle.command)
        val projects = resolveOwnerProjects(files, repoRoot)
        if (projects.isEmpty()) return OracleCommandResult.Command(oracle.command)

        return OracleCommandResult.Command(
            "pnpm nx run-many --target=$target --projects=" + projects.joinToString(",") + " --skip-nx-cache",
        )
    }

    /** Extracts the Nx target from a template command (`-t <value>` or `--target=<value>`). */
    fun extractTarget(command: String): String? {
        val shortMatch = Regex("(?:^|\\s)-t\\s+(\\S+)").find(command)
        if (shortMatch != null) return shortMatch.groupValues[1]
        val longMatch = Regex("(?:^|\\s)--target=(\\S+)").find(command)
        if (longMatch != null) return longMatch.groupValues[1]
        return null
    }
}
