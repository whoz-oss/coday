package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level guard-rails against runaway drift — both in iteration count and in cost.
 *
 * These limits are independent and measure different things — do not merge them:
 * - [caseMaxIterations] is a per-message guard in [io.whozoss.agentos.caseFlow.CaseRuntime]:
 *   it counts internal steps across the whole turn (agent selections, agent runs, redirects)
 *   and resets to 0 on each [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent].
 * - [agentMaxIterations] is a per-agent-run guard in
 *   [io.whozoss.agentos.agent.AgentAdvanced]: it counts intention→tool cycles within a
 *   single agent invocation and does not reset between turns.
 * - [runCostThreshold] is a cost guard: the amount AgentOS accepts to spend without human
 *   supervision.
 *
 * ## Three distinct behaviours on breach
 *
 * Do NOT assume symmetry between these limits — each reacts differently, and treating one
 * like another would introduce a behaviour that does not exist in the code:
 * - [caseMaxIterations] → **terminal**: the case transitions to
 *   [io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR].
 * - [agentMaxIterations] → **graceful degradation**: the agent emits a
 *   [io.whozoss.agentos.sdk.caseEvent.WarnEvent], breaks out of its loop and still produces
 *   a final response from what it gathered. The case is not terminated.
 * - [runCostThreshold] → **negotiable**: the breach triggers a user decision (raise the
 *   limit and continue, or stop). It is never an error state.
 *
 * All three exist to prevent runaway drift, not to calibrate performance. The defaults
 * are intentionally generous so that legitimate orchestration chains are never interrupted.
 *
 * Bound from the `agentos.limits` prefix in `application.yml`.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - `AGENTOS_LIMITS_CASE_MAX_ITERATIONS`  (default: 100)
 * - `AGENTOS_LIMITS_AGENT_MAX_ITERATIONS` (default: 20)
 * - `AGENTOS_LIMITS_RUN_COST_THRESHOLD`   (default: 10.0)
 *
 * Example (`application.yml`):
 * ```yaml
 * agentos:
 *   limits:
 *     case-max-iterations: 100
 *     agent-max-iterations: 20
 *     run-cost-threshold: 10.0
 * ```
 */
@ConfigurationProperties(prefix = "agentos.limits")
data class LimitsConfigProperties(
    /**
     * Maximum number of internal steps per user message in [io.whozoss.agentos.caseFlow.CaseRuntime].
     *
     * A "step" is one call to `processNextStep()` — covering agent selection,
     * agent run, and redirect events. The counter resets to 0 each time an
     * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] is processed, so
     * a case with multiple sequential commands can safely exceed this threshold
     * across the full session without triggering it within a single turn.
     *
     * Exceeding this limit transitions the case to [io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR].
     *
     * Defaults to 100.
     */
    val caseMaxIterations: Int = 100,
    /**
     * Maximum number of intention→tool iterations per agent run in
     * [io.whozoss.agentos.agent.AgentAdvanced].
     *
     * Each iteration consists of one intention-generation call followed by
     * zero or one tool executions. When this limit is reached the agent emits
     * a [io.whozoss.agentos.sdk.caseEvent.WarnEvent] and breaks out of the loop,
     * then produces its final response based on what it has gathered so far.
     *
     * Defaults to 20.
     */
    val agentMaxIterations: Int = 20,
    /**
     * Maximum amount AgentOS accepts to spend WITHOUT human verification, expressed in the
     * platform's single implicit currency unit (see [io.whozoss.agentos.usage.UsageRecord]).
     *
     * This is an anti-drift guard-rail, **not a budget**: it bounds what is spent between two
     * human interventions. It does not cap a user's total spending — the same user may open
     * several cases and spend a multiple of this threshold across them. Per-user or
     * per-period quotas are a different concern, served by
     * [io.whozoss.agentos.usage.UsageRecordRepository.aggregateByUser].
     *
     * ## Negotiable, never an error
     *
     * Unlike [caseMaxIterations], breaching this threshold must NEVER transition the case to
     * [io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR]. The intended behaviour is to hand
     * control back to the user, who either raises the limit for that case and continues, or
     * stops. Spending beyond the threshold is a legitimate outcome of an explicit human
     * decision, not a failure.
     *
     * ## Resolution chain (not implemented yet)
     *
     * `Case.runCostThreshold ?: Namespace.runCostThreshold ?: this platform default`
     *
     * On both entities `null` means "inherit from the level above" — never "zero" and never
     * "unlimited". A case that materialises its own value leaves the inherited regime and is
     * no longer affected by later namespace changes.
     *
     * Defaults to 10.0.
     */
    val runCostThreshold: Double = 10.0,
)
