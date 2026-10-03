package io.whozoss.factory.web

import io.whozoss.factory.config.CockpitProperties
import org.springframework.core.io.PathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the vanilla cockpit assets served by `factory-service`.
 *
 * The directory is configurable through `factory.cockpit.assets-dir`
 * ([CockpitProperties]). A relative value is tried against the process working
 * directory, then against its parent, so `factory/dashboard` resolves both when
 * the service is launched from the repository root and when it is launched from
 * `factory-service/` (the default for `bootRun` and the Gradle test JVM).
 *
 * Every lookup is confined to the resolved root: a request path containing
 * `..` or escaping the root always yields `null` (no directory traversal).
 */
@Component
class CockpitAssets(properties: CockpitProperties) {

    private val candidates: List<Path> = candidatePaths(properties.assetsDir)

    /** The readable cockpit directory, or `null` when the cockpit is absent. */
    val directory: Path? = candidates.firstOrNull { Files.isDirectory(it) && Files.isReadable(it) }

    /**
     * The static-resource location Spring serves from for a cockpit sub-path
     * (`""` for the root, `"js"`, `"css"`, …). Falls back to the first
     * candidate (which may not exist yet) so the handler still initializes and
     * simply answers 404 until the assets are deployed.
     */
    fun location(subPath: String = ""): Resource {
        val root = directory ?: candidates.first()
        return PathResource(if (subPath.isEmpty()) root else root.resolve(subPath))
    }

    /** Resolve a single asset by its request-relative path, refusing traversal. */
    fun resolve(relativePath: String): Resource? {
        val root = directory ?: return null
        val normalized = relativePath.trimStart('/')
        if (normalized.isEmpty() || normalized.contains("..")) return null
        val candidate = root.resolve(normalized).normalize()
        if (!candidate.startsWith(root)) return null
        if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) return null
        return PathResource(candidate)
    }

    private fun candidatePaths(configured: String): List<Path> {
        val trimmed = configured.trim().removePrefix("file:").trim()
        val raw = if (trimmed.isEmpty()) DEFAULT_ASSETS_DIR else trimmed
        val path = Paths.get(raw)
        val candidates = mutableListOf(path.toAbsolutePath().normalize())
        if (!path.isAbsolute) {
            // `bootRun` and the Gradle test JVM run from `factory-service/`, so
            // `factory/dashboard` lives one level up from the working directory.
            Paths.get("").toAbsolutePath().normalize().parent?.let { candidates.add(it.resolve(path).normalize()) }
        }
        return candidates.distinct()
    }

    private companion object {
        const val DEFAULT_ASSETS_DIR = "factory/dashboard"
    }
}
