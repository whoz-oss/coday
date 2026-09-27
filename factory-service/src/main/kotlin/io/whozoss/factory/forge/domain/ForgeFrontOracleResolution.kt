package io.whozoss.factory.forge.domain

import java.nio.file.Files
import java.nio.file.Path

/** Input of [ForgeFrontOracleResolution.resolveFrontOraclePlan]. */
data class FrontOraclePlanInput(
    val repoRoot: String,
    val files: List<String>,
    val hostMapRaw: Any?,
    val buildTemplate: String,
    val testsTarget: String = "frontend-test",
    val requireBuild: Boolean = true,
    val projectInspector: (String, String) -> Map<String, Any?> = ForgeFrontOracleResolution::inspectNxProject,
)

/**
 * Application service for the front-domain oracle resolution.
 *
 * Port of `factory/src/application/forge-bmad/forge-front-oracle-resolution.ts`.
 * Owner-project discovery, build-host mapping and effective Nx inspection. The
 * Nx inspection is injectable so the service stays testable without the CLI.
 */
object ForgeFrontOracleResolution {

    const val FRONT_ORACLE_MAP_SCHEMA_VERSION = 1

    private val VALID_NAME = Regex("^[A-Za-z0-9._-]+$")

    private fun fail(code: String, message: String): Nothing = throw ForgeCodedException(code, message)

    private fun validName(name: Any?): Boolean = name is String && VALID_NAME.matches(name)

    internal fun readProject(path: Path, label: String): Map<String, Any?> {
        return try {
            val config = ForgeJson.parseObject(Files.readString(path))
            if (!validName(config["name"])) fail("ORACLE_INFRASTRUCTURE", "$label has an absent or invalid Nx project name.")
            config
        } catch (error: ForgeCodedException) {
            throw error
        } catch (_: Exception) {
            fail("ORACLE_INFRASTRUCTURE", "Cannot read $label.")
        }
    }

    private fun hostProject(root: Path, name: String): Map<String, Any?>? {
        for (relative in listOf(Path.of("apps", name, "project.json"), Path.of("frontend", "apps", name, "project.json"), Path.of(name, "project.json"))) {
            val candidate = root.resolve(relative)
            if (Files.exists(candidate)) return readProject(candidate, "Build host project.json for $name")
        }
        return null
    }

    /** Resolve owner names together with the exact project.json encountered for each file. */
    fun resolveOwnerProjectConfigs(files: List<String>, repoRoot: String): List<Map<String, Any?>> {
        val root = Path.of(repoRoot).toAbsolutePath().normalize()
        val byName = LinkedHashMap<String, Map<String, Any?>>()
        for (file in files) {
            if (file.isEmpty() || Path.of(file).isAbsolute) {
                fail("ORACLE_INFRASTRUCTURE", "Invalid StoryEdit file path: $file.")
            }
            val absolute = root.resolve(file).normalize()
            if (!absolute.startsWith(root)) fail("ORACLE_INFRASTRUCTURE", "StoryEdit file escapes repository root: $file.")
            var dir: Path? = absolute.parent
            var found = false
            while (dir != null && dir.startsWith(root)) {
                val projectPath = dir.resolve("project.json")
                if (Files.exists(projectPath)) {
                    val config = readProject(projectPath, "Owner project.json for $file")
                    val name = config["name"].toString()
                    val previous = byName[name]
                    if (previous != null && previous["__projectPath"] != projectPath.toString()) {
                        fail("ORACLE_INFRASTRUCTURE", "Nx owner $name resolves to multiple project.json files.")
                    }
                    if (previous == null) byName[name] = config + mapOf("__projectPath" to projectPath.toString())
                    found = true
                    break
                }
                dir = dir.parent
            }
            if (!found) continue
        }
        return byName.values.toList()
    }

    /** Reads one project's effective Nx configuration through the Nx CLI. */
    fun inspectNxProject(name: String, repoRoot: String): Map<String, Any?> {
        if (!validName(name)) fail("ORACLE_INFRASTRUCTURE", "Invalid Nx project name for inspection: $name.")
        val output = try {
            val process = ProcessBuilder("pnpm", "nx", "show", "project", name, "--json")
                .directory(Path.of(repoRoot).toFile())
                .redirectErrorStream(false)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                fail("ORACLE_INFRASTRUCTURE", "Cannot inspect effective Nx configuration for $name.")
            }
            text
        } catch (error: ForgeCodedException) {
            throw error
        } catch (_: Exception) {
            fail("ORACLE_INFRASTRUCTURE", "Cannot inspect effective Nx configuration for $name.")
        }
        val config = try {
            ForgeJson.parseObject(output)
        } catch (_: Exception) {
            fail("ORACLE_INFRASTRUCTURE", "Effective Nx configuration for $name is not valid JSON.")
        }
        return config
    }

    private fun inspectEffectiveProject(
        name: String,
        repoRoot: String,
        inspector: (String, String) -> Map<String, Any?>,
    ): Map<String, Any?> {
        val config = try {
            inspector(name, repoRoot)
        } catch (error: ForgeCodedException) {
            if (error.code == "ORACLE_INFRASTRUCTURE") throw error
            fail("ORACLE_INFRASTRUCTURE", "Cannot inspect effective Nx configuration for $name.")
        } catch (_: Exception) {
            fail("ORACLE_INFRASTRUCTURE", "Cannot inspect effective Nx configuration for $name.")
        }
        val targets = config["targets"]
        if (config["name"] != name || targets !is Map<*, *>) {
            fail("ORACLE_INFRASTRUCTURE", "Effective Nx configuration for $name is invalid or mismatched.")
        }
        return config
    }

    /** Parse and validate the `FACTORY_FRONT_BUILD_HOST_MAP` JSON environment variable. */
    fun parseFrontBuildHostMap(raw: Any?): Map<String, List<String>> {
        if (raw !is String || raw.isEmpty()) fail("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP is required.")
        val map = try {
            ForgeJson.parseObject(raw)
        } catch (_: Exception) {
            fail("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP must be valid JSON.")
        }
        for ((owner, hosts) in map) {
            if ((owner != "*" && !validName(owner)) ||
                hosts !is List<*> || hosts.isEmpty() || hosts.any { !validName(it) }
            ) {
                fail("ORACLE_INFRASTRUCTURE", "Host map contains an invalid owner or host.")
            }
        }
        return map.mapValues { (_, hosts) -> (hosts as List<*>).map { it.toString() }.distinct().sorted() }
    }

    /** Resolve the front build/tests command plan for a StoryEdit file set. */
    @Suppress("UNCHECKED_CAST")
    fun resolveFrontOraclePlan(input: FrontOraclePlanInput): Map<String, Any?> {
        val ownerProjects = resolveOwnerProjectConfigs(input.files, input.repoRoot)
        val owners = ownerProjects.map { it["name"].toString() }
        if (owners.isEmpty()) fail("ORACLE_INFRASTRUCTURE", "No Nx owner project found for StoryEdit files.")
        val inspected = HashMap<String, Map<String, Any?>>()
        fun inspect(name: String): Map<String, Any?> =
            inspected.getOrPut(name) { inspectEffectiveProject(name, input.repoRoot, input.projectInspector) }

        val map = if (input.requireBuild) parseFrontBuildHostMap(input.hostMapRaw) else null
        val hosts = mutableListOf<String>()
        val ownersWithTestTarget = mutableListOf<String>()
        val ownersWithoutTestTarget = mutableListOf<String>()
        for (owner in ownerProjects) {
            val name = owner["name"].toString()
            if (input.requireBuild) {
                val mapped = map!![name] ?: map["*"]
                if (mapped == null) fail("ORACLE_INFRASTRUCTURE", "No build host mapping for owner $name.")
                for (host in mapped) {
                    if (hostProject(Path.of(input.repoRoot), host) == null) {
                        fail("ORACLE_INFRASTRUCTURE", "Build host $host does not exist.")
                    }
                    val targets = inspect(host)["targets"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                    if (targets["build"] == null && targets["build-angular"] == null) {
                        fail("ORACLE_INFRASTRUCTURE", "Build host $host has no build target.")
                    }
                    if (host !in hosts) hosts.add(host)
                }
            }
            val targets = inspect(name)["targets"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (targets[input.testsTarget] != null) ownersWithTestTarget.add(name) else ownersWithoutTestTarget.add(name)
        }
        val buildHosts = hosts.sorted()
        val build = linkedMapOf<String, Any?>(
            "command" to if (input.requireBuild) "${input.buildTemplate} --projects=${buildHosts.joinToString(",")}" else null,
            "cwd" to input.repoRoot,
            "owners" to owners,
            "buildHosts" to buildHosts,
            "target" to "build",
            "configuration" to "development",
        )
        val tests = linkedMapOf<String, Any?>(
            "command" to if (ownersWithTestTarget.isNotEmpty()) {
                "pnpm nx run-many --target=${input.testsTarget} --projects=${ownersWithTestTarget.joinToString(",")} --skip-nx-cache"
            } else {
                null
            },
            "cwd" to input.repoRoot,
            "owners" to ownersWithTestTarget,
            "ownersWithTestTarget" to ownersWithTestTarget,
            "ownersWithoutTestTarget" to ownersWithoutTestTarget,
            "buildHosts" to emptyList<String>(),
            "target" to input.testsTarget,
            "configuration" to null,
        )
        return linkedMapOf(
            "schemaVersion" to FRONT_ORACLE_MAP_SCHEMA_VERSION,
            "owners" to owners,
            "ownersWithTestTarget" to ownersWithTestTarget,
            "ownersWithoutTestTarget" to ownersWithoutTestTarget,
            "build" to build,
            "tests" to tests,
            "commandHash" to ForgeJson.sha256(ForgeJson.stringify(linkedMapOf("build" to build, "tests" to tests))),
        )
    }
}
