package io.whozoss.factory.verification.registry

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Run outcome. `pass` | `fail`. */
enum class RunStatus {
    PASS,
    FAIL,
    ;

    val wire: String get() = name.lowercase()
}

/** Phase kind. `agent` | `code`. */
enum class PhaseKind {
    AGENT,
    CODE,
    ;

    val wire: String get() = name.lowercase()
}

/** An open run. */
data class Run(
    val runId: String,
    val filePath: Path,
    val startedAtMillis: Long,
    val namespaceId: String? = null,
)

/** An open phase inside a run. */
data class Phase(
    val name: String,
    val startedAtMillis: Long,
    val run: Run,
)

/**
 * Append-only JSONL run registry — ported from `factory/src/lib/registry.ts`.
 *
 * Guarantees preserved:
 *  - **Fail by default**: [startPhase] writes `status: "fail"` immediately. A phase
 *    only becomes `pass` when [passPhase] is called explicitly.
 *  - **Append-only**: each record is one JSON line appended to
 *    `<runsDir>/<runId>.jsonl`; nothing is ever rewritten.
 *  - **Byte-compatible format**: `run_start` / `phase` / `phase_end` / `run_end`
 *    with the exact field order and ISO-8601 millisecond timestamps the
 *    instrument writes, so the runtime keeps reading it unchanged.
 *  - **Facts are not opinions**: `facts` is a plain map under the `facts` key and is
 *    never overwritten.
 */
class RunRegistry(
    private val runsDir: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: (Instant) -> String = ::defaultRunId,
) {

    private val mapper = ObjectMapper()
    private var currentRun: Run? = null
    private val closedRunIds = mutableSetOf<String>()

    fun getCurrentRun(): Run? = currentRun

    /** Creates a run and appends its `run_start` record. */
    fun createRun(workflowName: String, namespaceId: String? = null): Run {
        Files.createDirectories(runsDir)
        val instant = clock.instant()
        val runId = idFactory(instant)
        val filePath = runsDir.resolve("$runId.jsonl")

        val record = linkedMapOf<String, Any?>(
            "kind" to "run_start",
            "runId" to runId,
            "workflow" to workflowName,
            "startedAt" to iso(instant),
        )
        if (!namespaceId.isNullOrEmpty()) record["namespaceId"] = namespaceId
        appendLine(filePath, record)

        val run = Run(
            runId = runId,
            filePath = filePath,
            startedAtMillis = clock.millis(),
            namespaceId = namespaceId,
        )
        currentRun = run
        return run
    }

    /** Starts a phase, writing `status: "fail"` immediately (fail-by-default). */
    fun startPhase(run: Run, name: String, kind: PhaseKind): Phase {
        appendLine(
            run.filePath,
            linkedMapOf(
                "kind" to "phase",
                "name" to name,
                "phaseKind" to kind.wire,
                "status" to RunStatus.FAIL.wire,
                "startedAt" to iso(clock.instant()),
            ),
        )
        return Phase(name = name, startedAtMillis = clock.millis(), run = run)
    }

    /** Ends a phase with `status: "pass"`. */
    fun passPhase(phase: Phase, facts: Map<String, Any?> = emptyMap()) {
        endPhase(phase, RunStatus.PASS, facts)
    }

    /** Ends a phase with `status: "fail"`. */
    fun failPhase(phase: Phase, facts: Map<String, Any?> = emptyMap()) {
        endPhase(phase, RunStatus.FAIL, facts)
    }

    private fun endPhase(phase: Phase, status: RunStatus, facts: Map<String, Any?>) {
        appendLine(
            phase.run.filePath,
            linkedMapOf(
                "kind" to "phase_end",
                "name" to phase.name,
                "status" to status.wire,
                "durationMs" to (clock.millis() - phase.startedAtMillis),
                "facts" to facts,
            ),
        )
    }

    /** Ends a run. `facts` is only written when non-empty, as in the Node instrument. */
    fun endRun(run: Run, status: RunStatus, facts: Map<String, Any?> = emptyMap()) {
        val record = linkedMapOf<String, Any?>(
            "kind" to "run_end",
            "status" to status.wire,
            "durationMs" to (clock.millis() - run.startedAtMillis),
            "endedAt" to iso(clock.instant()),
        )
        if (facts.isNotEmpty()) record["facts"] = facts
        appendLine(run.filePath, record)
    }

    /** Ends the current run at most once. Returns `true` when it wrote the record. */
    fun endCurrentRunOnce(status: RunStatus, facts: Map<String, Any?> = emptyMap()): Boolean {
        val run = currentRun ?: return false
        if (run.runId in closedRunIds) return false
        endRun(run, status, facts)
        closedRunIds.add(run.runId)
        return true
    }

    private fun appendLine(filePath: Path, record: Map<String, Any?>) {
        Files.writeString(
            filePath,
            mapper.writeValueAsString(record) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    companion object {
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        private val RANDOM = SecureRandom()

        private val TRAILING_MILLIS = Regex("\\.\\d{3}Z$")

        /**
         * `20260927T113335Z-<4 hex chars>` — same shape as the Node `generateRunId`
         * (`toISOString` with `-`/`:` stripped, milliseconds collapsed, 2 random bytes).
         */
        fun defaultRunId(instant: Instant): String {
            val timestamp = ISO.format(instant)
                .replace("-", "")
                .replace(":", "")
                .replace(TRAILING_MILLIS, "Z")
            val bytes = ByteArray(2)
            RANDOM.nextBytes(bytes)
            return "$timestamp-" + bytes.joinToString("") { "%02x".format(it) }
        }

        private fun iso(instant: Instant): String = ISO.format(instant)
    }
}
