package io.whozoss.agentos.plugins.ktlint

import io.whozoss.agentos.sdk.tool.BlockingTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tool that autocorrects the ktlint violations of Kotlin files, REWRITING them in place.
 *
 * Works on files only (never a whole tree) and is time-boxed by a mandatory `timeoutSeconds`: when it expires,
 * the files already rewritten stay modified and are listed. Cancelling the call stops the run between two files
 * (the file in progress is finished first).
 *
 * The business logic ([format]) is blocking and returns a [KtlintToolResult]; [executeBlocking] maps it to a
 * [ToolExecutionResult] (`KTLINT_FAILURE` / `KTLINT_ERROR` error types). Running it interruptibly on the IO
 * dispatcher, so that cancelling the case releases the caller and the worker, is the job of [BlockingTool].
 */
class KtlintFormatTool internal constructor(
    private val bounded: BoundedKtlintRun,
    configName: String? = null,
) : BlockingTool<KtlintFormatTool.Input>() {
    /** Run on the real ktlint engine, for the Kotlin files of [projectRoot]. */
    constructor(projectRoot: Path, configName: String? = null) : this(
        bounded = BoundedKtlintRun(projectRoot = projectRoot),
        configName = configName,
    )

    /** Input of the tool, as sent by the LLM. Nullable fields with defaults so partial JSON deserializes. */
    data class Input(
        val files: List<String>? = null,
        val timeoutSeconds: Int? = null,
    )

    override val name: String = prefixedToolName(configName = configName, toolName = TOOL_NAME)

    override val description: String =
        """Autocorrect Kotlin code style with ktlint (standard ruleset, the project's .editorconfig applies). REWRITES the files in place.
Works on files only, never on a directory or a whole project: pass the .kt / .kts files one by one.
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the formatting stops when it is exceeded,
the files already rewritten stay modified and are listed.
Returns how many violations were fixed, which files were modified,
and the violations ktlint cannot autocorrect, which must be fixed by hand.
A run that leaves violations is answered as a KTLINT_FAILURE whose text is the report (the files it fixed are still rewritten);
KTLINT_ERROR means the formatting itself could not run."""

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        ToolSchema.schema(
            required = listOf("files", "timeoutSeconds"),
            properties =
                mapOf(
                    "files" to ToolSchema.stringArray(description = KtlintToolTexts.FILES_PARAM_DESCRIPTION),
                    "timeoutSeconds" to ToolSchema.integer(description = KtlintToolTexts.TIMEOUT_PARAM_DESCRIPTION),
                ),
        )

    override fun executeBlocking(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: return missingTimeoutResult()
        val timeoutSeconds = params.timeoutSeconds ?: return missingTimeoutResult()
        return format(files = params.files.orEmpty(), timeoutSeconds = timeoutSeconds).toToolExecutionResult()
    }

    /**
     * Autocorrects [files] in place and reports what was fixed, which files were modified and what remains.
     *
     * @return [KtlintToolResult.Success] when nothing is left to fix by hand, [KtlintToolResult.Failure] with the
     * report when violations remain, [KtlintToolResult.Error] when the formatting could not run (invalid timeout or
     * files, timeout, failure).
     */
    fun format(
        files: List<String>,
        timeoutSeconds: Int,
    ): KtlintToolResult {
        val done = CopyOnWriteArrayList<FormatResult>()
        return bounded.execute(
            files = files,
            timeoutSeconds = timeoutSeconds,
            onTimeout = { alreadyModified(done = done) },
        ) { resolved ->
            resolved.forEach { file ->
                BoundedKtlintRun.ensureRunning()
                done += bounded.runner.format(file = file)
            }
            val modifiedFiles = done.filter { it.modified }.map { it.file }
            val remaining = done.flatMap { it.remaining }
            val report =
                buildString {
                    append("Formatted ${resolved.size} file(s): ")
                    append("${done.sumOf { it.correctedCount }} violation(s) fixed, ")
                    append("${remaining.size} remaining, ")
                    append("${modifiedFiles.size} file(s) modified")
                    modifiedFiles.forEach { append("\n  modified: $it") }
                    remaining.forEach { append("\n${it.describe()}") }
                }
            KtlintToolResult.ofViolations(remaining = remaining.size, text = report)
        }
    }

    private fun alreadyModified(done: List<FormatResult>): String =
        done
            .filter { it.modified }
            .joinToString(separator = "") { "\n  modified: ${it.file}" }
            .let { modified -> if (modified.isEmpty()) "" else "\nFiles already modified before the timeout:$modified" }

    companion object {
        const val TOOL_NAME = "ktlint_format"
    }
}
