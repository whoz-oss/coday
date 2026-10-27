package io.whozoss.factory.agentattempt.domain

/**
 * Observability contract mapping the Factory's durable attempt verdicts to the
 * sealing distinction exposed to the cockpit and other consumers
 * (Phase 10 — governance of terminal states).
 *
 * ## Two different state axes
 *
 * The AgentOS runtime and the Factory control plane own DISTINCT state axes
 * that must never be conflated:
 *
 *  1. **AgentOS runtime states** (the `CaseStatus` of a case, mirrored here as
 *     import-free string constants — see the constants below for provenance):
 *     - [PENDING] — case created, not yet started;
 *     - [RUNNING] — an agent turn is in progress (proves only that the
 *       execution started, never a success);
 *     - [IDLE] — the turn completed; the runtime is alive, quiescent, waiting
 *       for the next user message. NOT a terminal state and NOT a verdict;
 *     - [KILLED] — the case was permanently destroyed by an explicit kill.
 *       Terminal on the runtime axis;
 *     - [ERROR] — the case terminated on an unrecoverable error. Terminal on
 *       the runtime axis;
 *     - [ARCHIVED] / [CLOSED_BY_USER] — consumer-facing distinctions of a
 *       case record that was retired from the active list (soft-close /
 *       `removed` flag on the AgentOS `CaseDto`). These are LIST-VISIBILITY
 *       labels of the runtime record, kept here as documented constants only
 *       (never imported): they say nothing about the Factory verdict.
 *
 *  2. **Factory sealed verdicts** ([AgentAttemptStatus]): the authoritative,
 *     immutable outcome of a durable attempt. Terminal statuses
 *     (`succeeded`, `failed`, `indeterminate`, `interrupted`, `superseded`)
 *     are sealed on entry and never rewritten (Phase 10 late-result policy).
 *
 * ## The core rule: no success by silence
 *
 * A runtime state NEVER seals a success on its own. `KILLED`, `ERROR`,
 * `IDLE`, an archived/closed case record, or plain silence map at best to
 * [SealingClass.RUNTIME_CLOSED] — the runtime is over but the Factory verdict
 * may stay `indeterminate`. The ONLY path that seals [SealingClass.COMPLETED]
 * is a structured, capability-backed result submission
 * (`POST /api/factory/agent-step-results`) that finalized the durable attempt
 * as [AgentAttemptStatus.SUCCEEDED]. This mirrors the verdict rule enforced
 * by `io.whozoss.factory.adapter.agentos.VerdictDeriver` (a raw agent message
 * or a quiescent status is never an authoritative success) — this type keeps
 * its own import-free view exactly like `VerdictDeriver`/`CaseEventView` do,
 * and deliberately never imports `io.whozoss.agentos.sdk.caseFlow.CaseStatus`.
 *
 * ## Consumer contract
 *
 * [SealingClass] is the distinction the cockpit (and any consumer of
 * [DurableAgentAttemptDto]) uses to separate:
 *  - `ACTIVE` — the attempt is still being observed; nothing is sealed;
 *  - `RUNTIME_CLOSED` — the attempt is sealed WITHOUT an authoritative
 *    success (`failed` / `indeterminate` / `interrupted` / `superseded`);
 *  - `COMPLETED` — the attempt is sealed by the authoritative
 *    capability-backed success;
 *  - `ARCHIVED` — reserved for records retired by an operator; no
 *    [AgentAttemptStatus] maps to it today (archival is a lifecycle concern
 *    above the attempt aggregate), it exists so consumers have a stable label
 *    to render retired records distinctly from runtime-closed ones.
 */
object AgentOsRuntimeStateMapping {

    /** AgentOS `CaseStatus.PENDING` — case created, not yet started. */
    const val PENDING = "PENDING"

    /** AgentOS `CaseStatus.RUNNING` — agent turn in progress; never a success. */
    const val RUNNING = "RUNNING"

    /** AgentOS `CaseStatus.IDLE` — runtime alive and quiescent; NOT a verdict. */
    const val IDLE = "IDLE"

    /** AgentOS `CaseStatus.KILLED` — runtime terminal, permanently destroyed. */
    const val KILLED = "KILLED"

    /** AgentOS `CaseStatus.ERROR` — runtime terminal, unrecoverable error. */
    const val ERROR = "ERROR"

    /**
     * Consumer-facing label of a case record retired from the active list
     * (AgentOS `CaseDto` soft-close / `removed`). List-visibility only — it
     * never seals a Factory verdict.
     */
    const val ARCHIVED = "ARCHIVED"

    /**
     * Consumer-facing label of a case closed by the user from the cockpit.
     * Runtime-record distinction only — it never seals a Factory verdict.
     */
    const val CLOSED_BY_USER = "CLOSED_BY_USER"

    /**
     * The runtime states that are terminal on the AgentOS axis: the case will
     * never produce another event. A terminal runtime state alone NEVER maps
     * to [SealingClass.COMPLETED] — "no success by silence".
     */
    val RUNTIME_TERMINAL_STATES: Set<String> = setOf(KILLED, ERROR)

    /**
     * The sealing distinction of a durable attempt verdict, exposed to the
     * cockpit and consumers as [DurableAgentAttemptDto.sealingClass].
     */
    enum class SealingClass {
        /** Observing, nothing sealed: the attempt lifecycle is still open. */
        ACTIVE,

        /**
         * Sealed WITHOUT an authoritative success: the runtime is over (or the
         * attempt was interrupted/superseded) and the terminal verdict is
         * `failed` / `indeterminate` / `interrupted` / `superseded`. Immutable.
         */
        RUNTIME_CLOSED,

        /**
         * Sealed by the authoritative capability-backed success
         * ([AgentAttemptStatus.SUCCEEDED]). Reachable ONLY through a
         * structured result submission — never from a runtime state, silence
         * or free text. Immutable.
         */
        COMPLETED,

        /**
         * Operator-retired record. No [AgentAttemptStatus] maps to it today;
         * the label exists so consumers can render retired records distinctly.
         */
        ARCHIVED,
    }

    /**
     * Classify the sealing of a durable attempt [status] for observability.
     *
     * Non-terminal statuses are [SealingClass.ACTIVE]; [AgentAttemptStatus.SUCCEEDED]
     * is the ONLY status classified [SealingClass.COMPLETED]; every other
     * terminal status is [SealingClass.RUNTIME_CLOSED]. [SealingClass.ARCHIVED]
     * is never produced by this mapping — archival lives above the attempt
     * aggregate.
     */
    fun classify(agentAttemptStatus: AgentAttemptStatus): SealingClass = when {
        !agentAttemptStatus.terminal -> SealingClass.ACTIVE
        agentAttemptStatus.isSuccess -> SealingClass.COMPLETED
        else -> SealingClass.RUNTIME_CLOSED
    }
}
