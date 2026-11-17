package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

internal const val CLEAN_SOURCE = "val answer = 42\n"
internal const val TRAILING_SPACE_SOURCE = "val answer = 42 \n"
internal const val WILDCARD_IMPORT_SOURCE = "import java.util.*\n\nval answer = 42\n"

/** Generous timeout for tests that must not hit it. */
internal const val TIMEOUT_SECONDS = 60

/** Longer than any test timeout: only an interruption ends it. */
private const val HANG_MILLIS = 60_000L

private const val NANOS_PER_MILLI = 1_000_000L

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

/**
 * Runner that works instantly on every file but the ones named in [hangingOn], which block until interrupted.
 *
 * It records what it did so tests can tell which files were handled: [processed] lists the files entered, in order,
 * [hangStarted] opens when a hanging file is entered and [hangInterrupted] when its wait was interrupted.
 */
internal class HangingRunner(
    private val hangingOn: Set<String>,
) : StyleRunner {
    val processed = CopyOnWriteArrayList<String>()
    val hangStarted = CountDownLatch(1)
    val hangInterrupted = CountDownLatch(1)

    override fun lint(file: Path): List<KtlintViolation> {
        enter(file = file)
        return emptyList()
    }

    override fun format(file: Path): FormatResult {
        enter(file = file)
        return FormatResult(file = file.fileName.toString(), modified = true, correctedCount = 1, remaining = emptyList())
    }

    private fun enter(file: Path) {
        val name = file.fileName.toString()
        processed += name
        if (name in hangingOn) hang()
    }

    private fun hang() {
        hangStarted.countDown()
        try {
            Thread.sleep(HANG_MILLIS)
        } catch (e: InterruptedException) {
            hangInterrupted.countDown()
            throw e
        }
    }
}

/**
 * Runner that works instantly on every file but the ones named in [busyOn], which keep the thread busy for
 * [busyMillis] WITHOUT looking at interruption, like the real ktlint engine parsing a file.
 *
 * [processed] lists the files entered, in order, [busyStarted] opens when a busy file is entered and [busyFinished]
 * when it is done.
 */
internal class BusyRunner(
    private val busyOn: Set<String>,
    private val busyMillis: Long,
) : StyleRunner {
    val processed = CopyOnWriteArrayList<String>()
    val busyStarted = CountDownLatch(1)
    val busyFinished = CountDownLatch(1)

    override fun lint(file: Path): List<KtlintViolation> {
        enter(file = file)
        return emptyList()
    }

    override fun format(file: Path): FormatResult {
        enter(file = file)
        return FormatResult(file = file.fileName.toString(), modified = true, correctedCount = 1, remaining = emptyList())
    }

    private fun enter(file: Path) {
        val name = file.fileName.toString()
        processed += name
        if (name in busyOn) work()
    }

    private fun work() {
        busyStarted.countDown()
        val deadline = System.nanoTime() + busyMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) Thread.onSpinWait()
        busyFinished.countDown()
    }
}
