package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryCockpitProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.PathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves an optional external Factory Cockpit, then the copy embedded in the JAR. */
@Component
class FactoryCockpitAssets(properties: FactoryCockpitProperties) {

    private val externalRoot: Path? = properties.assetsDir
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.removePrefix("file:")
        ?.let(Paths::get)
        ?.let { if (it.isAbsolute) it.normalize() else Paths.get("").toAbsolutePath().resolve(it).normalize() }
        ?.takeIf { Files.isDirectory(it) && Files.isReadable(it) }

    fun location(): Resource = externalRoot?.let(::PathResource) ?: ClassPathResource(CLASSPATH_ROOT)

    /** Resolve one asset while refusing traversal outside an external directory. */
    fun resolve(relativePath: String): Resource? {
        val normalized = relativePath.trimStart('/')
        if (normalized.isEmpty() || normalized.contains("..")) return null
        return when (val root = externalRoot) {
            null -> ClassPathResource("$CLASSPATH_ROOT$normalized").takeIf { it.exists() && it.isReadable }
            else -> root.resolve(normalized).normalize()
                .takeIf { it.startsWith(root) && Files.isRegularFile(it) && Files.isReadable(it) }
                ?.let(::PathResource)
        }
    }

    private companion object {
        const val CLASSPATH_ROOT = "static/factory/"
    }
}
