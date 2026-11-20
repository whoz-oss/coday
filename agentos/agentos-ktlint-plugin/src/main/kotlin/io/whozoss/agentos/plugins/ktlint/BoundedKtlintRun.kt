package io.whozoss.agentos.plugins.ktlint

import mu.KLogging
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What `ktlint_check` and `ktlint_format` have in common: validate the timeout, check and resolve the files,
 * then run the work in a time box. Shared by both tools, like the runner is shared by the Gradle tools.
 *
 * The ktlint engine runs in-process and has no process to kill: the work runs on a worker thread and the caller
 * waits with a timeout. Path confinement and the files-only rule live in [KotlinSourceResolver], the engine
 * behind [runner].
 *
 * @property runner the style engine the tools run their work with.
 */
internal class BoundedKtlintRun(
    projectRoot: Path,
    val runner: StyleRunner,
) {
    /** Run on the real ktlint engine. */
    constructor(projectRoot: Path) : this(projectRoot = projectRoot, runner = KtlintRunner(projectRoot = projectRoot.toRealPath()))

    private val sources = KotlinSourceResolver(projectRoot = projectRoot)

    private val worker = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Validates [timeoutSeconds] and [files], then runs [action] on the resolved files within [timeoutSeconds].
     *
     * On expiry the worker is interrupted: [ensureRunning] stops it before the next file, the file in progress is
     * finished first because the engine does not check interruption while parsing. The same happens when the
     * calling thread is interrupted while waiting (cancellation of the calling coroutine, see the tools' `execute`):
     * the worker is cancelled, the interrupt flag is restored and the answer is `ERROR: ktlint interrupted.`.
     *
     * @param onTimeout text appended to the timeout error, e.g. the files already modified.
     * @return the report of [action], or a text starting with `ERROR` (invalid timeout, invalid files, timeout, failure).
     */
    fun execute(
        files: List<String>,
        timeoutSeconds: Int,
        onTimeout: () -> String = { "" },
        action: (List<Path>) -> String,
    ): String =
        ToolTimeout.validate(seconds = timeoutSeconds)?.let { "ERROR: $it" }
            ?: resolve(files = files).fold(
                onSuccess = { resolved ->
                    runBounded(files = files, timeoutSeconds = timeoutSeconds, onTimeout = onTimeout) { action(resolved) }
                },
                onFailure = { error -> "ERROR: ${error.message}" },
            )

    private fun resolve(files: List<String>): Result<List<Path>> =
        try {
            require(files.isNotEmpty()) { "No file provided: pass at least one .kt / .kts file" }
            Result.success(sources.resolve(relativePaths = files))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }

    private fun runBounded(
        files: List<String>,
        timeoutSeconds: Int,
        onTimeout: () -> String,
        work: () -> String,
    ): String {
        val future = worker.submit(Callable { work() })
        return try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            logger.warn { "ktlint timed out after $timeoutSeconds seconds on files=$files" }
            "ERROR: ktlint timed out after $timeoutSeconds seconds. " +
                "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or fewer files.${onTimeout()}"
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            "ERROR: ktlint interrupted."
        } catch (e: ExecutionException) {
            logger.warn(e.cause) { "ktlint failed on files=$files" }
            "ERROR: ktlint failed: ${e.cause?.message}"
        }
    }

    companion object : KLogging() {
        /** To call between two files: stops the worker once the time box expired and interrupted it. */
        fun ensureRunning() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ktlint stopped: timeout reached")
        }
    }
}
