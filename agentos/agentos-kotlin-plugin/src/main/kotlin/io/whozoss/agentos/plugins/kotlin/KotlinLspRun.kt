package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.tool.ToolTimeout
import mu.KLogging
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What every Kotlin LSP tool does around its question: validate the timeout and the files, reach the server of the
 * project, and bound the whole call by the timeout of the agent. Shared by the tools of one integration, like the
 * bounded run of the ktlint plugin.
 *
 * Nothing reaches the server before the input is valid: a refused path, a missing project directory or an invalid
 * timeout is answered without starting it. The call then runs on a [KotlinLspWorkers] thread and the caller waits with
 * the timeout: on expiry the worker is interrupted, which cancels the pending request (see [awaitResult]); a server
 * still in its handshake is stopped and started again on the next call.
 *
 * @property paths the paths of the project, through which every file of an agent goes
 */
internal class KotlinLspRun(
    projectRoot: Path,
    private val pool: LspConnectionPool = LspConnectionPool.SHARED,
    private val worker: ExecutorService = KotlinLspWorkers.executor,
) {
    private val root: Path = projectRoot.toAbsolutePath().normalize()

    val paths: ProjectPaths = ProjectPaths(root = root)

    /**
     * Validates [timeoutSeconds] and [files], then runs [work] against the server of the project within [timeoutSeconds].
     *
     * @param files the files the call reads or writes, as the agent gave them, checked before the server is reached
     * @return the result of [work], or a [KotlinToolResult.Error] (invalid timeout or file, timeout, failure)
     */
    fun execute(
        timeoutSeconds: Int,
        files: List<String> = emptyList(),
        work: (lsp: LspContext) -> KotlinToolResult,
    ): KotlinToolResult =
        ToolTimeout.validate(seconds = timeoutSeconds)?.let { KotlinToolResult.Error(text = it) }
            ?: refusal(files = files)
            ?: runBounded(timeoutSeconds = timeoutSeconds, work = work)

    /** [execute] of a question about [symbol]: the symbol is resolved first, its explanation answered when it cannot be. */
    fun navigate(
        timeoutSeconds: Int,
        symbol: String,
        file: String?,
        line: Int?,
        action: (lsp: LspContext, target: Resolution.Resolved, renderer: LocationRenderer) -> KotlinToolResult,
    ): KotlinToolResult =
        execute(
            timeoutSeconds = timeoutSeconds,
            files = listOfNotNull(file),
            work = { lsp ->
                lsp.navigate(symbol = symbol, file = file, line = line, action = {
                    target,
                    renderer,
                    ->
                    action(lsp, target, renderer)
                })
            },
        )

    /** Why the call cannot run on [files], or null when it can: the project directory must exist, every file must be allowed. */
    fun refusal(files: List<String>): KotlinToolResult.Error? =
        when {
            !Files.isDirectory(root) -> KotlinToolResult.Error(text = "The Kotlin project directory does not exist: $root")
            else -> files.firstNotNullOfOrNull { file -> refusalOf(file = file) }
        }

    private fun refusalOf(file: String): KotlinToolResult.Error? =
        try {
            paths.checked(filePath = file)
            null
        } catch (e: IllegalArgumentException) {
            KotlinToolResult.Error(text = e.message.orEmpty())
        }

    private fun runBounded(
        timeoutSeconds: Int,
        work: (lsp: LspContext) -> KotlinToolResult,
    ): KotlinToolResult =
        try {
            awaitBounded(
                future = worker.submit(Callable { work(LspContext(connection = pool.obtain(root = root), paths = paths)) }),
                timeoutSeconds = timeoutSeconds,
            )
        } catch (_: RejectedExecutionException) {
            KotlinToolResult.Error(text = "The Kotlin plugin is stopping: retry in a moment.")
        }

    private fun awaitBounded(
        future: Future<KotlinToolResult>,
        timeoutSeconds: Int,
    ): KotlinToolResult =
        try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            logger.warn { "Kotlin LSP call on $root timed out after $timeoutSeconds seconds" }
            KotlinToolResult.Error(
                text =
                    "Kotlin LSP did not answer within $timeoutSeconds seconds. The server may still be starting or indexing, " +
                        "or the request is too broad: retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}), " +
                        "a more specific request, or fall back to a text search.",
            )
        } catch (_: InterruptedException) {
            // the worker is cancelled, the flag is set again: BlockingTool discards what is returned after it
            future.cancel(true)
            Thread.currentThread().interrupt()
            KotlinToolResult.Error(text = "Kotlin LSP call interrupted.")
        } catch (e: ExecutionException) {
            failureOf(cause = e.cause ?: e)
        }

    /**
     * A refusal of the plugin ([IllegalArgumentException]) or an unavailable server ([IllegalStateException], which
     * [LspInitializationException] is) carries a message meant for the agent. Anything else comes from the server.
     */
    private fun failureOf(cause: Throwable): KotlinToolResult =
        when (cause) {
            is IllegalArgumentException, is IllegalStateException -> {
                KotlinToolResult.Error(text = cause.message.orEmpty())
            }

            else -> {
                logger.warn(cause) { "Kotlin LSP call on $root failed" }
                KotlinToolResult.Error(text = "Kotlin LSP call failed: ${cause.message.orEmpty().take(MAX_FAILURE_CHARS)}")
            }
        }

    companion object : KLogging() {
        private const val MAX_FAILURE_CHARS = 300
    }
}

/** What a tool needs to talk to the server of its project, for the length of one call. */
internal class LspContext(
    val connection: LanguageServerConnection,
    val paths: ProjectPaths,
) {
    /** The text document service of the language server. */
    val textDocument: TextDocumentService get() = connection.server.textDocumentService

    /** The workspace service of the language server. */
    val workspace: WorkspaceService get() = connection.server.workspaceService

    /**
     * Announces [filePath] to the server and returns its URI, once the path is known to stay in the project.
     *
     * @throws IllegalArgumentException when the file is outside the project or sensitive
     */
    fun sync(filePath: String): String = connection.syncFile(filePath = paths.checked(filePath = filePath))

    /** A symbol source for this call, caching what it reads. */
    fun symbolSource(): ConnectionSymbolSource = ConnectionSymbolSource(connection = connection, paths = paths)

    /**
     * Resolves [symbol] to a position, then answers [action] with it; when resolution fails or is ambiguous
     * the answer is the explanation, so the agent knows how to refine the call.
     */
    fun navigate(
        symbol: String,
        file: String?,
        line: Int?,
        action: (target: Resolution.Resolved, renderer: LocationRenderer) -> KotlinToolResult,
    ): KotlinToolResult {
        val source = symbolSource()
        val renderer = LocationRenderer(paths = paths, sourceLines = source, enclosingSymbols = source)
        return when (val resolution = SymbolLocator(source = source, paths = paths).resolve(symbol = symbol, file = file, line = line)) {
            is Resolution.Resolved -> action(resolution, renderer)
            is Resolution.Unresolved -> KotlinToolResult.Error(text = resolution.message)
        }
    }

    /** Applies [edit] on disk and tells the server about the files it touched. */
    fun apply(edit: WorkspaceEdit): AppliedEdit =
        WorkspaceEditApplier(paths = paths).apply(edit = edit).also { applied ->
            applied.vacated.forEach { connection.closeFile(filePath = it) }
            applied.modified
                .filter { it.endsWith(".kt") || it.endsWith(".kts") }
                .forEach { connection.syncFile(filePath = it) }
        }

    /** [filePaths] shown one per line, relative to the project when inside it. */
    fun display(filePaths: List<String>): String = filePaths.joinToString(separator = "\n") { paths.displayPath(filePath = it) }
}
