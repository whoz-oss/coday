package io.whozoss.agentos.plugins.ktlint

import io.whozoss.agentos.sdk.tool.ToolTimeout
import mu.KLogging
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Why a bounded run stopped before its end, as told to the tools so that their answer can say what was done.
 *
 * @property moment how the answer names the stop.
 */
internal enum class RunEnd(
    val moment: String,
) {
    /** The time box expired and the worker ended (the file in progress was finished). */
    TIMED_OUT(moment = "timeout"),

    /** The time box expired and the worker is still finishing the file in progress. */
    TIMED_OUT_WORKER_BUSY(moment = "timeout"),

    /** The engine failed on a file (for example a syntax error); the worker ended. */
    FAILED(moment = "failure"),
}

/**
 * What `ktlint_check` and `ktlint_format` have in common: validate the timeout, check and resolve the files,
 * then run the work file by file in a time box. Shared by both tools, like the runner is shared by the Gradle tools.
 *
 * The ktlint engine runs in-process and has no process to kill: the work runs on a thread of [KtlintWorkers] (a
 * small shared pool) and the caller waits with a timeout. The time box starts at the submission, so a run that waits
 * for a free worker beyond it gives up without having started. Path confinement and the files-only rule live in
 * [KotlinSourceResolver], the engine behind [runner].
 *
 * The loop over the files, with the check that the worker was not interrupted before each file and the tracking of
 * the file in progress, lives here and nowhere else: a tool only says what to do with one file.
 *
 * @property runner the style engine the tools run their work with.
 * @property settleTimeout how long a timed-out run waits for its worker to finish the file in progress before answering.
 * @property worker where the work runs, asked at each run so that a pool shut down by a stop of the plugin is not
 *   kept: the pool shared by the plugin unless a test brings its own.
 */
internal class BoundedKtlintRun(
    projectRoot: Path,
    val runner: StyleRunner,
    private val settleTimeout: Duration = SETTLE_TIMEOUT,
    private val worker: () -> ExecutorService = { KtlintWorkers.executor },
) {
    /** Run on the real ktlint engine, the one shared by every run on this project ([KtlintRunnerCache]). */
    constructor(projectRoot: Path) : this(projectRoot = projectRoot, runner = KtlintRunnerCache.runnerFor(projectRoot = projectRoot))

    private val realRoot: Path = projectRoot.toRealPath()

    private val sources = KotlinSourceResolver(projectRoot = projectRoot)

    /**
     * Validates [timeoutSeconds] and [files], then calls [onFile] on each resolved file, one after the other, within
     * [timeoutSeconds], and finally [report] to build the answer.
     *
     * On expiry the worker is interrupted: the loop stops it before the next file, the file in progress is
     * finished first because the engine does not check interruption while parsing. The answer waits up to
     * [settleTimeout] for the worker to end, so that [onAbort] sees the file that was being written; when the
     * worker is still busy after that, [onAbort] is told so. A failure of the engine on a file is reported the
     * same way. The same happens when the calling thread is interrupted while waiting (cancellation of the calling
     * coroutine, see the tools' `execute`): the worker is cancelled, the interrupt flag is restored and the answer
     * is an [KtlintToolResult.Error]. When the pool of workers is not available (plugin stopped), the answer is an
     * [KtlintToolResult.Error] too.
     *
     * @param onFile what to do with one file; the tool keeps what it needs (violations, results) in its own accumulator.
     * @param report builds the answer once every file went through [onFile]; its argument is the number of files.
     * @param onAbort text appended to the timeout and failure errors, e.g. the files already modified and the failing
     *   file. Its first argument tells why the run stopped, and for a timeout whether the worker ended or is still
     *   busy; the second is the file the worker had reached (null between two files), the failing file on a failure.
     * @return the result of [report], or a [KtlintToolResult.Error] (invalid timeout, invalid files, timeout, failure).
     */
    fun execute(
        files: List<String>,
        timeoutSeconds: Int,
        onAbort: (end: RunEnd, current: Path?) -> String = { _, _ -> "" },
        onFile: (Path) -> Unit,
        report: (fileCount: Int) -> KtlintToolResult,
    ): KtlintToolResult =
        ToolTimeout.validate(seconds = timeoutSeconds)?.let { KtlintToolResult.Error(text = it) }
            ?: try {
                require(files.isNotEmpty()) { "No file provided: pass at least one .kt / .kts file" }
                val resolved = sources.resolve(relativePaths = files)
                val inProgress = AtomicReference<Path?>(null)
                runBounded(
                    files = files,
                    timeoutSeconds = timeoutSeconds,
                    onAbort = { end -> onAbort(end, inProgress.get()) },
                    work = {
                        resolved.forEach { file ->
                            ensureRunning()
                            inProgress.set(file)
                            onFile(file)
                            inProgress.set(null)
                        }
                        report(resolved.size)
                    },
                )
            } catch (e: IllegalArgumentException) {
                KtlintToolResult.Error(text = "${e.message}")
            } catch (e: IOException) {
                KtlintToolResult.Error(text = "${e.message}")
            }

    private fun runBounded(
        files: List<String>,
        timeoutSeconds: Int,
        onAbort: (RunEnd) -> String,
        work: () -> KtlintToolResult,
    ): KtlintToolResult {
        logger.info { "[BoundedKtlintRun] files=$files timeoutSeconds=$timeoutSeconds" }
        // The future of a cancelled task answers at once, whatever the worker is doing: its end is told by this latch.
        val ended = CountDownLatch(1)
        // QUEUED -> RUNNING when a worker takes the task, QUEUED -> GAVE_UP when the caller stops waiting before that.
        val state = AtomicReference(RunState.QUEUED)
        val future = submit(ended = ended, state = state, work = work)
        return when (future) {
            null -> {
                logger.warn { "ktlint workers are not available (plugin stopped?), files=$files" }
                KtlintToolResult.Error(text = "ktlint workers are not available: the plugin is stopped or stopping. Retry in a moment.")
            }

            else -> {
                await(
                    future = future,
                    ended = ended,
                    state = state,
                    files = files,
                    timeoutSeconds = timeoutSeconds,
                    onAbort = onAbort,
                )
            }
        }
    }

    /** The task handed to a worker, or null when the pool refuses it (shut down by a stop of the plugin). */
    private fun submit(
        ended: CountDownLatch,
        state: AtomicReference<RunState>,
        work: () -> KtlintToolResult,
    ): Future<KtlintToolResult>? =
        try {
            worker().submit(
                Callable {
                    try {
                        if (state.compareAndSet(RunState.QUEUED, RunState.RUNNING)) {
                            work()
                        } else {
                            KtlintToolResult.Error(text = "ktlint run abandoned before it started.")
                        }
                    } finally {
                        ended.countDown()
                    }
                },
            )
        } catch (_: RejectedExecutionException) {
            null
        }

    private fun await(
        future: Future<KtlintToolResult>,
        ended: CountDownLatch,
        state: AtomicReference<RunState>,
        files: List<String>,
        timeoutSeconds: Int,
        onAbort: (RunEnd) -> String,
    ): KtlintToolResult =
        try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            if (state.compareAndSet(RunState.QUEUED, RunState.GAVE_UP)) {
                future.cancel(false)
                logger.warn { "ktlint did not start within $timeoutSeconds seconds, workers busy, files=$files" }
                KtlintToolResult.Error(
                    text =
                        "ktlint could not start within $timeoutSeconds seconds: its workers are busy with other runs. " +
                            "Nothing was processed. Retry in a moment.",
                )
            } else {
                timedOut(future = future, ended = ended, files = files, timeoutSeconds = timeoutSeconds, onAbort = onAbort)
            }
        } catch (_: InterruptedException) {
            // the worker is cancelled, the flag is set again: BlockingTool discards what is returned after it
            future.cancel(true)
            Thread.currentThread().interrupt()
            logger.warn { "[BoundedKtlintRun] interrupted, files=$files" }
            KtlintToolResult.Error(text = "ktlint interrupted.")
        } catch (e: ExecutionException) {
            logger.warn(e.cause) { "ktlint failed on files=$files" }
            KtlintToolResult.Error(text = "ktlint failed: ${describeFailure(cause = e.cause)}${onAbort(RunEnd.FAILED)}")
        }

    /** The message of [cause], else its class name (a `StackOverflowError` has no message), else a generic text. */
    private fun describeFailure(cause: Throwable?): String =
        cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName ?: "unknown error"

    /** The run started and its time box expired: interrupt the worker, give it a moment to end, and say what it did. */
    private fun timedOut(
        future: Future<*>,
        ended: CountDownLatch,
        files: List<String>,
        timeoutSeconds: Int,
        onAbort: (RunEnd) -> String,
    ): KtlintToolResult {
        future.cancel(true)
        val settled = awaitEnd(ended = ended)
        val end = if (settled) RunEnd.TIMED_OUT else RunEnd.TIMED_OUT_WORKER_BUSY
        logger.warn { "ktlint timed out after $timeoutSeconds seconds on files=$files, worker ended=$settled" }
        return KtlintToolResult.Error(
            text =
                "ktlint timed out after $timeoutSeconds seconds. " +
                    "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or fewer files.${onAbort(end)}",
        )
    }

    /** Path of [file] relative to the project root, as the tools report it. */
    fun relativeName(file: Path): String = realRoot.relativize(file).toString()

    /** @return true when the worker ended within [settleTimeout]; an interrupted caller stops waiting and keeps its flag. */
    private fun awaitEnd(ended: CountDownLatch): Boolean =
        try {
            ended.await(settleTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private enum class RunState { QUEUED, RUNNING, GAVE_UP }

    companion object : KLogging() {
        /** Enough for the engine to finish a large file, short enough not to hold the agent when a file is stuck. */
        private val SETTLE_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** To call between two files: stops the worker once the time box expired and interrupted it. */
        private fun ensureRunning() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ktlint stopped: timeout reached")
        }
    }
}
