package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns agent-provided relative paths into the Kotlin source files ktlint must process.
 *
 * Single authority for two rules:
 * - confinement: every resolved path, symbolic links included, stays inside the project root;
 * - granularity: only files are accepted, never a directory, so ktlint never runs on a whole tree at once.
 */
class KotlinSourceResolver(
    projectRoot: Path,
) {
    private val realRoot: Path = projectRoot.toRealPath()

    /**
     * @return the real paths of the files, each once, sorted.
     * @throws IllegalArgumentException when a path leaves the project, does not exist, is a directory,
     *   or designates a file that is not Kotlin source.
     */
    fun resolve(relativePaths: List<String>): List<Path> =
        relativePaths
            .map { relativePath -> toKotlinFile(path = confine(relativePath = relativePath), relativePath = relativePath) }
            .distinct()
            .sorted()

    private fun confine(relativePath: String): Path {
        val lexical = realRoot.resolve(relativePath).normalize()
        require(lexical.startsWith(realRoot)) { "Path is outside the project: $relativePath" }
        require(Files.exists(lexical)) { "Path not found: $relativePath" }
        val real = lexical.toRealPath()
        require(real.startsWith(realRoot)) { "Path is outside the project: $relativePath" }
        return real
    }

    private fun toKotlinFile(
        path: Path,
        relativePath: String,
    ): Path {
        require(!Files.isDirectory(path)) {
            "Path is a directory: $relativePath. ktlint runs file by file: pass the .kt / .kts files one by one."
        }
        require(path.fileName.toString().substringAfterLast(delimiter = '.', missingDelimiterValue = "") in KOTLIN_EXTENSIONS) {
            "Path is not a Kotlin source file (.kt / .kts): $relativePath"
        }
        return path
    }

    companion object {
        private val KOTLIN_EXTENSIONS = setOf("kt", "kts")
    }
}
