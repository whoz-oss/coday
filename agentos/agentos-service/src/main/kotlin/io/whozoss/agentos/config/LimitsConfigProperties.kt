package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level iteration-limit guard-rails.
 *
 * These two limits are independent and measure different things — do not merge them:
 * - [caseMaxIterations] is a per-message guard in [io.whozoss.agentos.caseFlow.CaseRuntime]:
 *   it counts internal steps across the whole turn (agent selections, agent runs, redirects)
 *   and resets to 0 on each [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent].
 * - [agentMaxIterations] is a per-agent-run guard in
 *   [io.whozoss.agentos.agent.AgentAdvanced]: it counts intention→tool cycles within a
 *   single agent invocation and does not reset between turns.
 *
 * Both limits exist to prevent runaway loops, not to calibrate performance. The defaults
 * are intentionally generous so that legitimate orchestration chains are never interrupted.
 *
 * Bound from the `agentos.limits` prefix in `application.yml`.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - `AGENTOS_LIMITS_CASE_MAX_ITERATIONS`  (default: 100)
 * - `AGENTOS_LIMITS_AGENT_MAX_ITERATIONS` (default: 20)
 *
 * Example (`application.yml`):
 * ```yaml
 * agentos:
 *   limits:
 *     case-max-iterations: 100
 *     agent-max-iterations: 20
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
)
