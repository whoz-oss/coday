package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Files
import java.nio.file.Path

internal const val CLEAN_SOURCE = "val answer = 42\n"
internal const val TRAILING_SPACE_SOURCE = "val answer = 42 \n"
internal const val WILDCARD_IMPORT_SOURCE = "import java.util.*\n\nval answer = 42\n"

/** Generous timeout for tests that must not hit it. */
internal const val TIMEOUT_SECONDS = 60

/** Longer than any test timeout: only an interruption ends it. */
private const val HANG_MILLIS = 60_000L

/** Runs [block] on a fresh temporary project directory (real path) that is deleted afterwards. */
internal inline fun <T> withProject(block: (Path) -> T): T {
    val projectDir = Files.createTempDirectory("agentos-ktlint-test").toRealPath()
    try {
        return block(projectDir)
    } finally {
        projectDir.toFile().deleteRecursively()
    }
}

/** Writes [content] to [relativePath] under this directory, creating the parent directories. */
internal fun Path.writeFile(
    relativePath: String,
    content: String = CLEAN_SOURCE,
): Path =
    resolve(relativePath).also { file ->
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

/** Runner that works instantly on every file but the ones named in [hangingOn], which block until interrupted. */
internal class HangingRunner(
    private val hangingOn: Set<String>,
) : StyleRunner {
    override fun lint(file: Path): List<KtlintViolation> {
        if (file.fileName.toString() in hangingOn) Thread.sleep(HANG_MILLIS)
        return emptyList()
    }

    override fun format(file: Path): FormatResult {
        if (file.fileName.toString() in hangingOn) Thread.sleep(HANG_MILLIS)
        return FormatResult(file = file.fileName.toString(), modified = true, correctedCount = 1, remaining = emptyList())
    }
}
