package io.whozoss.factory.capability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.config.FrontVerificationProperties
import io.whozoss.factory.verification.oracle.OracleExecutor
import io.whozoss.factory.verification.oracle.TaskOutcomeCounter
import io.whozoss.factory.verification.manifest.VerificationRunResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.springframework.stereotype.Component

const val FRONT_VERIFICATION_INFRASTRUCTURE = "FRONT_VERIFICATION_INFRASTRUCTURE"
const val FRONT_VERIFICATION_EMPTY_SUCCESS = "FRONT_VERIFICATION_EMPTY_SUCCESS"

sealed interface FactoryFrontVerificationResolution {
    data class Executed(val run: VerificationRunResult.Executed) : FactoryFrontVerificationResolution
    data class Refused(val code: String, val message: String) : FactoryFrontVerificationResolution
    data object NotFactoryOwned : FactoryFrontVerificationResolution
}

data class FactoryFrontVerificationPlan(
    val name: String,
    val command: String,
    val repoRoot: Path,
    val ownerProjects: List<String>,
    val selectedProjects: List<String>,
    val target: String,
    val timeoutMs: Long,
)

fun interface FrontCommandRunner {
    fun run(plan: FactoryFrontVerificationPlan): VerificationRunResult.Executed
}

fun interface NxProjectResolver {
    fun resolve(project: String, repoRoot: Path): NxProjectResolution
}

data class NxProjectResolution(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/** Factory-owned frontend verification. */
@Component
class FactoryFrontVerificationRegistry(
    private val properties: FrontVerificationProperties,
    private val runner: FrontCommandRunner = FrontCommandRunner { plan ->
        val result = OracleExecutor.runCommand(plan.command, plan.repoRoot, plan.timeoutMs)
        VerificationRunResult.Executed(
            plan.name, plan.command, plan.timeoutMs, result.exitCode, result.stdout, result.stderr,
            result.durationMs, result.timedOut,
        )
    },
    private val nxProjectResolver: NxProjectResolver = NxProjectResolver { project, repoRoot ->
        val result = OracleExecutor.runCommand(
            "pnpm nx show project $project --json",
            repoRoot,
            NX_RESOLUTION_TIMEOUT_MS,
        )
        NxProjectResolution(result.exitCode, result.stdout, result.stderr, result.timedOut)
    },
) {
    fun resolve(name: String, repoRoot: Path, modifiedFiles: List<String>): FactoryFrontVerificationResolution {
        if (name !in NAMES) return FactoryFrontVerificationResolution.NotFactoryOwned
        return when (val planned = plan(name, repoRoot, modifiedFiles)) {
            is PlanResult.Refused -> FactoryFrontVerificationResolution.Refused(planned.code, planned.message)
            is PlanResult.Ready -> {
                val run = runner.run(planned.plan)
                if (run.exitCode == 0 && !run.timedOut && noRecognizableWork(run.stdout, run.stderr)) {
                    FactoryFrontVerificationResolution.Refused(
                        FRONT_VERIFICATION_EMPTY_SUCCESS,
                        "Nx returned success but reported no recognizable projects or tasks for '${planned.plan.name}'.",
                    )
                } else FactoryFrontVerificationResolution.Executed(run)
            }
        }
    }

    fun plan(name: String, repoRoot: Path, modifiedFiles: List<String>): PlanResult = try {
        val root = repoRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return refused("Validated run worktree does not exist: $root")
        val realRoot = root.toRealPath()
        if (modifiedFiles.isEmpty()) return refused("No modified files were claimed by the accepted frontend agent result.")

        val owners = LinkedHashSet<String>()
        for (file in modifiedFiles) {
            val path = safeClaimPath(file, realRoot) ?: return refused("Modified-file claim is invalid or escapes the run worktree: $file")
            val owner = findOwner(path, realRoot)
                ?: return refused("Modified-file claim has no Nx owner project: $file")
            owners.add(owner.name)
        }
        if (owners.isEmpty()) return refused("No Nx owner project was found for the frontend modified-file claims.")

        val target = if (name == FRONTEND_BUILD) properties.buildTarget else properties.testTarget
        if (!SAFE_TOKEN.matches(target)) return refused("Configured target has an unsafe token: '$target'.")
        val selected = if (name == FRONTEND_BUILD) {
            if (properties.buildHosts.isEmpty()) return refused("No centralized frontend build hosts are configured.")
            properties.buildHosts.map { host ->
                if (!SAFE_TOKEN.matches(host)) return refused("Configured build host has an unsafe project token: '$host'.")
                val project = findProjectByName(host, realRoot)
                    ?: return refused("Configured build host '$host' has no unique confined project.json.")
                requireResolvedTarget(project, target, realRoot, "Configured build host '$host'")
                    ?.let { return refused(it) }
                host
            }.distinct().also {
                if (it.size != properties.buildHosts.size) return refused("Configured frontend build hosts must be unique.")
            }
        } else {
            owners.map { owner ->
                val project = findProjectByName(owner, realRoot)
                    ?: return refused("Owner project '$owner' has no unique confined project.json.")
                requireResolvedTarget(project, target, realRoot, "Owner project '$owner'")
                    ?.let { return refused(it) }
                owner
            }
        }
        selected.forEach { if (!SAFE_TOKEN.matches(it)) return refused("Unsafe Nx project token: '$it'.") }
        val configuration = properties.buildConfiguration
        if (name == FRONTEND_BUILD && configuration.isNotBlank() && !SAFE_TOKEN.matches(configuration)) {
            return refused("Configured build configuration has an unsafe token: '$configuration'.")
        }
        val configArg = if (name == FRONTEND_BUILD && configuration.isNotBlank()) " --configuration=$configuration" else ""
        val command = "pnpm nx run-many --target=$target --projects=${selected.joinToString(",")}$configArg --skip-nx-cache"
        PlanResult.Ready(
            FactoryFrontVerificationPlan(name, command, realRoot, owners.toList(), selected, target, properties.timeoutMs),
        )
    } catch (e: InvalidPathException) {
        refused("Invalid filesystem path: ${e.input}")
    } catch (e: Exception) {
        refused("Frontend verification planning failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private data class ProjectConfig(val name: String, val path: Path, val json: JsonNode)

    private fun safeClaimPath(value: String, root: Path): Path? {
        if (value.isBlank() || value.contains('\u0000') || value.contains('\\')) return null
        val relative = Path.of(value)
        if (relative.isAbsolute) return null
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) return null
        var existing: Path? = resolved
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        val realExisting = existing?.toRealPath() ?: return null
        return if (realExisting.startsWith(root)) resolved else null
    }

    private fun findOwner(file: Path, root: Path): ProjectConfig? {
        var dir: Path? = file.parent
        while (dir != null && dir.startsWith(root)) {
            val candidate = dir.resolve("project.json")
            if (Files.exists(candidate)) return readProject(candidate, root)
            dir = dir.parent
        }
        return null
    }

    private fun findProjectByName(name: String, root: Path): ProjectConfig? {
        val matches = mutableListOf<ProjectConfig>()
        Files.walk(root).use { paths ->
            paths.filter { it.fileName?.toString() == "project.json" }.forEach { path ->
                readProject(path, root)?.takeIf { it.name == name }?.let(matches::add)
            }
        }
        return matches.singleOrNull()
    }

    private fun readProject(path: Path, root: Path): ProjectConfig? {
        val real = path.toRealPath()
        if (!real.startsWith(root) || !Files.isRegularFile(real)) return null
        val json = MAPPER.readTree(real.toFile())
        val name = json.get("name")?.takeIf { it.isTextual }?.asText() ?: return null
        if (!SAFE_TOKEN.matches(name)) return null
        return ProjectConfig(name, real, json)
    }

    private fun requireResolvedTarget(project: ProjectConfig, target: String, root: Path, description: String): String? {
        val result = try {
            nxProjectResolver.resolve(project.name, root)
        } catch (e: Exception) {
            return "Cannot inspect effective Nx configuration for '${project.name}': ${e.message ?: e.javaClass.simpleName}."
        }
        if (result.timedOut) return "Effective Nx configuration inspection timed out for '${project.name}'."
        if (result.exitCode != 0) return "Cannot inspect effective Nx configuration for '${project.name}' (exit ${result.exitCode})."
        val effective = try {
            MAPPER.readTree(result.stdout)
        } catch (_: Exception) {
            return "Effective Nx configuration for '${project.name}' is not valid JSON."
        }
        if (!effective.isObject || effective.get("name")?.takeIf { it.isTextual }?.asText() != project.name) {
            return "Effective Nx configuration for '${project.name}' is invalid or mismatched."
        }
        val targets = effective.get("targets")
        if (targets == null || !targets.isObject) {
            return "Effective Nx configuration for '${project.name}' has no valid targets object."
        }
        if (targets.get(target) == null) return "$description does not expose configured Nx target '$target'."
        return null
    }

    private fun noRecognizableWork(stdout: String, stderr: String): Boolean {
        val output = "$stdout\n$stderr"
        if (NO_WORK.containsMatchIn(output)) return true
        val counts = TaskOutcomeCounter.countTaskOutcomes(output)
        val total = counts.executed + counts.fromCache + counts.upToDate + counts.skipped
        return total == 0 && !counts.summaryFound
    }

    private fun refused(message: String) = PlanResult.Refused(FRONT_VERIFICATION_INFRASTRUCTURE, message)

    sealed interface PlanResult {
        data class Ready(val plan: FactoryFrontVerificationPlan) : PlanResult
        data class Refused(val code: String, val message: String) : PlanResult
    }

    companion object {
        const val FRONTEND_BUILD = "frontend-build"
        const val FRONTEND_TESTS = "frontend-tests"
        val NAMES = setOf(FRONTEND_BUILD, FRONTEND_TESTS)
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private val NO_WORK = Regex("(?i)(no projects? (?:matched|found)|no tasks? (?:were )?(?:run|executed)|0 projects?)")
        private const val NX_RESOLUTION_TIMEOUT_MS = 10_000L
        private val MAPPER = ObjectMapper()
    }
}
