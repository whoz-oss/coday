package io.whozoss.factory.verification.oracle

/**
 * Oracle execution models — ported from `factory/src/domain/oracle/oracle.ts` and
 * `factory/src/application/oracle/oracle-executor.ts`.
 *
 * The verdict of an oracle is always `exitCode == 0`; nothing else. The models
 * here only record facts about what happened — they never derive a verdict from
 * the text of the output.
 */

/**
 * Why an Nx summary line was or was not found.
 *  - [NoNxTasks]: no `> nx run` line seen (Gradle or empty output) — normal.
 *  - [FreshRun]: Nx tasks were counted but no Nx summary line was seen — an
 *    unexpected output format worth recording.
 *  - `null` when a summary was found.
 */
enum class OracleSummaryAbsenceReason { NO_NX_TASKS, FRESH_RUN }

/**
 * A coarse count of what a build command actually did. This is a recorded fact,
 * never a verdict.
 */
data class TaskOutcomes(
    val upToDate: Int,
    val fromCache: Int,
    val skipped: Int,
    val executed: Int,
    val summaryFound: Boolean,
    val summaryAbsenceReason: OracleSummaryAbsenceReason?,
    val summaryFromCache: Int?,
    val summaryTotal: Int?,
    val countMismatch: Boolean,
)

/** A bounded excerpt of captured process output. */
data class BoundedOutput(
    val excerpt: String,
    val truncated: Boolean,
)

/** Result of a shell command run to completion (or timeout). */
data class RunCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
)

/** A coarse classification of an oracle execution. Never a content verdict. */
enum class OracleClassification { CLEAN, PRODUCT_REGRESSION, EMPTY_SUCCESS, ORACLE_INFRASTRUCTURE }

/** The outcome attached to a classification: `pass`, `fail` or `indeterminate`. */
enum class OracleOutcome {
    PASS,
    FAIL,
    INDETERMINATE,
    ;

    /** Lowercase wire value, matching the Node instrument (`'pass'`/`'fail'`/`'indeterminate'`). */
    val wire: String
        get() = name.lowercase()
}

/** The observation [OracleExecutor.classifyOracleExecution] needs. */
data class OracleExecutionObservation(
    val exitCode: Int?,
    val signal: String?,
    val timedOut: Boolean,
    val spawnError: String?,
    val counts: TaskOutcomes,
)

/** A coarse classification plus its outcome. */
data class OracleExecutionClassification(
    val classification: OracleClassification,
    val outcome: OracleOutcome,
)

/** Oracle definition subset used by execution. */
data class OracleExecutionDefinition(
    val argv: List<String>,
    val timeoutMs: Long,
    val requireWork: Boolean,
)

/** Full oracle execution result, including classification. */
data class OracleExecutionResult(
    val classification: OracleClassification,
    val outcome: OracleOutcome,
    val exitCode: Int?,
    val signal: String?,
    val timedOut: Boolean,
    val durationMs: Long,
    val spawnError: String?,
    val counts: TaskOutcomes,
    val stdout: BoundedOutput,
    val stderr: BoundedOutput,
)

/** An oracle artifact: the bounded raw output and its content hash. */
data class OracleArtifact(
    val raw: String,
    val hash: String,
)
