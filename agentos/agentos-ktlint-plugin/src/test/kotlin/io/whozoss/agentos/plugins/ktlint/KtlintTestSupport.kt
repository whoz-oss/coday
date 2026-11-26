package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal const val CLEAN_SOURCE = "val answer = 42\n"
internal const val TRAILING_SPACE_SOURCE = "val answer = 42 \n"
internal const val WILDCARD_IMPORT_SOURCE = "import java.util.*\n\nval answer = 42\n"

/** Not valid Kotlin: the engine refuses to parse it. */
internal const val NOT_KOTLIN_SOURCE = "fun (( {{ val = = \n"

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

/** A pool of [threads] daemon threads, for a test that needs to control how many runs can be served at once. */
internal fun daemonPool(threads: Int): ExecutorService =
    Executors.newFixedThreadPool(threads) { task -> Thread(task).apply { isDaemon = true } }

/**
 * Waits until a pool of ONE thread has ended its current task, without a delay: a task submitted now runs only once
 * the previous one is over.
 */
internal fun ExecutorService.awaitIdle() {
    submit(Callable { }).get(10, TimeUnit.SECONDS)
}

/** Pool that opens [submitted] once a run has been handed to [delegate], so a test knows the run is queued. */
internal class SignalingExecutor(
    private val delegate: ExecutorService,
) : ExecutorService by delegate {
    val submitted = CountDownLatch(1)

    override fun <T> submit(task: Callable<T>): Future<T> = delegate.submit(task).also { submitted.countDown() }
}

/**
 * A run on its own threads, so that tests whose runner hangs or stays busy never delay one another on the small pool
 * the plugin shares. [worker] is the pool the run asks at each run.
 */
internal fun isolatedRun(
    projectRoot: Path,
    runner: StyleRunner,
    settleTimeout: Duration = Duration.ofSeconds(5),
    worker: ExecutorService = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } },
): BoundedKtlintRun = BoundedKtlintRun(projectRoot = projectRoot, runner = runner, settleTimeout = settleTimeout, worker = { worker })

/**
 * Runner that works instantly on every file but the ones named in [failingOn], on which the engine fails, like ktlint
 * does on a file that is not valid Kotlin. Every other file has one violation and is reported as rewritten.
 */
internal class FailingRunner(
    private val failingOn: Set<String>,
) : StyleRunner {
    override fun lint(file: Path): List<KtlintViolation> {
        failIfListed(file = file)
        return listOf(
            KtlintViolation(
                file = file.fileName.toString(),
                line = 1,
                column = 1,
                ruleId = "test:rule",
                detail = "violation",
                autoCorrectable = false,
            ),
        )
    }

    override fun format(file: Path): FormatResult {
        failIfListed(file = file)
        return FormatResult(file = file.fileName.toString(), modified = true, correctedCount = 1, remaining = emptyList())
    }

    private fun failIfListed(file: Path) {
        check(file.fileName.toString() !in failingOn) { "Not a valid Kotlin file" }
    }
}

/**
 * Runner that works instantly on every file but the ones named in [busyOn], which keep the thread busy WITHOUT looking
 * at interruption, like the real ktlint engine parsing a file: for [busyMillis], or until [release] is opened when
 * one is given (a test that must not depend on how fast the machine is gives a long [busyMillis] and opens [release]
 * itself).
 *
 * [processed] lists the files entered, in order, [busyStarted] opens when a busy file is entered and [busyFinished]
 * when it is done.
 */
internal class BusyRunner(
    private val busyOn: Set<String>,
    private val busyMillis: Long,
    private val release: CountDownLatch? = null,
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
        while (System.nanoTime() < deadline && release?.let { it.count > 0 } != false) Thread.onSpinWait()
        busyFinished.countDown()
    }
}

/** Runner whose engine fails on every file with the throwable made by [failure], whatever its message. */
internal class ThrowingRunner(
    private val failure: () -> Throwable,
) : StyleRunner {
    override fun lint(file: Path): List<KtlintViolation> = throw failure()

    override fun format(file: Path): FormatResult = throw failure()
}

/**
 * Runner that finds [perFile] violations on every file (line n for the n-th, so a report shows which were kept) and,
 * for format, leaves them all to fix by hand; on the files named in [failingOn] the engine fails.
 */
internal class ManyViolationsRunner(
    private val perFile: Int,
    private val failingOn: Set<String> = emptySet(),
) : StyleRunner {
    override fun lint(file: Path): List<KtlintViolation> = violationsOf(file = file)

    override fun format(file: Path): FormatResult =
        FormatResult(
            file = file.fileName.toString(),
            modified = false,
            correctedCount = 0,
            remaining = violationsOf(file = file),
        )

    private fun violationsOf(file: Path): List<KtlintViolation> {
        val name = file.fileName.toString()
        check(name !in failingOn) { "Not a valid Kotlin file" }
        return (1..perFile).map { line ->
            KtlintViolation(file = name, line = line, column = 1, ruleId = "test:rule", detail = "violation", autoCorrectable = false)
        }
    }
}

/** Waits until [latch] opens, bounded, so that a test that went wrong fails instead of hanging. */
internal fun awaitSignal(latch: CountDownLatch): Boolean = latch.await(10, TimeUnit.SECONDS)
