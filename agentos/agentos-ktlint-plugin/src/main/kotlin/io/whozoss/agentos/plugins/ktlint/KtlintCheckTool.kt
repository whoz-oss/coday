package io.whozoss.agentos.plugins.ktlint

import io.whozoss.agentos.sdk.tool.BlockingTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolSchema
import io.whozoss.agentos.sdk.tool.ToolTimeout
import io.whozoss.agentos.sdk.tool.prefixedToolName
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Tool that reports the ktlint violations of Kotlin files, without ever modifying them.
 *
 * Works on files only (never a whole tree) and is time-boxed by a mandatory `timeoutSeconds`. When the run stops
 * before its end (timeout, or the engine failing on a file), the answer still reports the violations of the files
 * checked before and, for a failure, names the failing file.
 *
 * The business logic ([check]) is blocking and returns a [KtlintToolResult]; [executeBlocking] maps it to a
 * [ToolExecutionResult] (`KTLINT_FAILURE` / `KTLINT_ERROR` error types). Running it interruptibly on the IO
 * dispatcher, so that cancelling the case releases the caller and the worker, is the job of [BlockingTool].
 */
class KtlintCheckTool internal constructor(
    private val bounded: BoundedKtlintRun,
    configName: String? = null,
) : BlockingTool<KtlintCheckTool.Input>() {
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
        """Check Kotlin code style with ktlint (standard ruleset, the project's .editorconfig applies). Never modifies files.
Works on files only, never on a directory or a whole project: pass the .kt / .kts files one by one.
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the check stops when it is exceeded.
Returns one line per violation: file:line:column message (rule-id), flagged [auto-correctable] when ktlint_format can fix it.
A run that finds violations is answered as a KTLINT_FAILURE whose text is the report; KTLINT_ERROR means the check itself could not run."""

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
    ): ToolExecutionResult =
        when (val timeoutSeconds = input?.timeoutSeconds) {
            null -> missingTimeoutResult()
            else -> check(files = input?.files.orEmpty(), timeoutSeconds = timeoutSeconds).toToolExecutionResult()
        }

    /**
     * Reports the violations of [files], one line each, or that there is none.
     *
     * @return [KtlintToolResult.Success] when no file has a violation, [KtlintToolResult.Failure] with the report
     * when some have, [KtlintToolResult.Error] when the check could not run (invalid timeout or files, timeout, failure).
     */
    fun check(
        files: List<String>,
        timeoutSeconds: Int,
    ): KtlintToolResult {
        val found = CopyOnWriteArrayList<KtlintViolation>()
        val inProgress = AtomicReference<Path?>(null)
        return bounded.execute(
            files = files,
            timeoutSeconds = timeoutSeconds,
            onAbort = { end -> abortDetails(end = end, found = found, current = inProgress.get()) },
        ) { resolved ->
            resolved.forEach { file ->
                BoundedKtlintRun.ensureRunning()
                inProgress.set(file)
                found += bounded.runner.lint(file = file)
                inProgress.set(null)
            }
            if (found.isEmpty()) {
                KtlintToolResult.Success(text = "Checked ${resolved.size} file(s): no violation")
            } else {
                KtlintToolResult.Failure(
                    text =
                        "Checked ${resolved.size} file(s): ${found.size} violation(s)\n" +
                            found.joinToString(separator = "\n") { it.describe() },
                )
            }
        }
    }

    /**
     * What the agent must know when the run stopped before its end: the violations of the files checked so far and,
     * on a failure, the file the engine failed on ([current]), after which nothing was checked.
     */
    private fun abortDetails(
        end: RunEnd,
        found: List<KtlintViolation>,
        current: Path?,
    ): String {
        val violations =
            found
                .joinToString(separator = "") { "\n${it.describe()}" }
                .let { lines -> if (lines.isEmpty()) "" else "\nViolations found in the files checked before the ${end.moment}:$lines" }
        val failing =
            current
                ?.takeIf { end == RunEnd.FAILED }
                ?.let { "\nFailing file: ${bounded.relativeName(file = it)}. The files after it were not checked." }
                .orEmpty()
        return violations + failing
    }

    companion object {
        const val TOOL_NAME = "ktlint_check"
    }
}

/** Parameter descriptions shared by the two ktlint tools. */
internal object KtlintToolTexts {
    const val FILES_PARAM_DESCRIPTION =
        "Relative paths of .kt / .kts files, never directories, e.g. [\"engine/src/main/Foo.kt\", \"cli/src/Main.kt\"]. " +
            "Directories are refused."

    private const val ON_EXPIRY = "the ktlint run stops"

    /** Message returned when the agent did not provide `timeoutSeconds` at all. */
    val MISSING_TIMEOUT_MESSAGE: String = ToolTimeout.missingMessage(onExpiry = ON_EXPIRY)

    val TIMEOUT_PARAM_DESCRIPTION: String =
        ToolTimeout.paramDescription(onExpiry = ON_EXPIRY) +
            "Size it to the number of files: ktlint needs a few seconds per file, about 60 is enough for a handful."
}
