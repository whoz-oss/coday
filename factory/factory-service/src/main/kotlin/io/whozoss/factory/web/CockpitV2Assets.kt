package io.whozoss.factory.web

import io.whozoss.factory.config.CockpitV2Properties
import org.springframework.core.io.PathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the Cockpit V2 (Angular) assets served by `factory-service`.
 *
 * The directory is configurable through `factory.cockpit.v2.assets-dir`
 * ([CockpitV2Properties]) and defaults to the Angular build output
 * `apps/factory-cockpit/dist/browser`. A relative value is tried against the
 * process working directory and each of its ancestors. This covers both a
 * repository-root launch and Gradle's `factory/factory-service/` working
 * directory used by `bootRun`.
 *
 * Angular's application builder writes the browser bundle into `dist/browser`
 * (with a sibling `dist/` for server/prerender output), so when the configured
 * value ends with `browser` the parent `dist` directory is probed as a fallback
 * too.
 *
 * Every lookup is confined to the resolved root: a request path containing `..`
 * or escaping the root always yields `null` (no directory traversal).
 */
@Component
class CockpitV2Assets(properties: CockpitV2Properties) {

    private val candidates: List<Path> = candidatePaths(properties.assetsDir)

    /** The readable Cockpit V2 directory, or `null` when the assets are absent. */
    val directory: Path? = candidates.firstOrNull { Files.isDirectory(it) && Files.isReadable(it) }

    /**
     * The static-resource location Spring serves from. Falls back to the first
     * candidate (which may not exist yet) so the handler still initializes and
     * simply answers 404 until the assets are deployed.
     */
    fun location(): Resource {
        val root = directory ?: candidates.first()
        return PathResource(root)
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
        val bases = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }.toList()

        val candidates = mutableListOf<Path>()
        for (base in bases) {
            val resolved = base.resolve(path).normalize()
            candidates.add(resolved)
            // Angular emits into `dist/browser`; also accept the parent `dist`.
            if (path.fileName?.toString() == BROWSER_DIR) {
                path.parent?.let { candidates.add(base.resolve(it).normalize()) }
            }
        }
        return candidates.distinct()
    }

    private companion object {
        const val DEFAULT_ASSETS_DIR = "apps/factory-cockpit/dist/browser"
        const val BROWSER_DIR = "browser"
    }
}
