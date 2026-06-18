package io.whozoss.agentos.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Records Micrometer metrics for agent tool calls and confirmation outcomes.
 *
 * All metrics are exported automatically to Datadog (or any other configured
 * Micrometer registry) without additional wiring. Datadog export is opt-in:
 * it requires DATADOG_API_KEY to be set and
 * `management.datadog.metrics.export.enabled=true` in application configuration.
 *
 * ## Metrics produced
 *
 * ### `agentos.tool.calls.total` (Counter)
 * Incremented once per tool execution (success or failure).
 * Tags:
 * - `tool.name`        — fully-qualified tool name, e.g. `FILES__read`
 * - `integration.type` — integration prefix before `__`, e.g. `FILES` (or `unknown`
 *                         when the name contains no `__` separator)
 * - `agent.name`       — name of the agent that called the tool
 * - `namespace.id`     — UUID of the namespace the case belongs to
 * - `status`           — `success` or `failure`
 *
 * ### `agentos.tool.calls.duration` (Timer)
 * Distribution of wall-clock tool execution durations, measured with [Timer.Sample]
 * (nanosecond precision via [MeterRegistry.config().clock()]).
 * Same tags as `agentos.tool.calls.total`.
 * Use [startTimer] before execution and [stopTimer] after to record a sample.
 *
 * ### `agentos.tool.parameter_generation.failures` (Counter)
 * Incremented when [AgentAdvanced] exhausts all retry attempts and falls back
 * to `args = null` for a tool call.
 * Tags:
 * - `tool.name`    — fully-qualified tool name
 * - `agent.name`   — name of the agent
 * - `namespace.id` — UUID of the namespace
 *
 * ### `agentos.tool.confirmation.total` (Counter)
 * Incremented once per confirmation resolution.
 * Tags:
 * - `tool.name`    — fully-qualified tool name
 * - `agent.name`   — name of the agent
 * - `namespace.id` — UUID of the namespace
 * - `outcome`      — `applied`, `rejected`, or `aborted`
 */
@Service
class ToolMetricsService(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Starts a [Timer.Sample] using the registry's clock.
     *
     * Call this immediately before the tool execution block, then pass the returned
     * sample to [stopTimer] in both the success and error branches.
     *
     * Returns `null` only when the meter registry is unavailable (defensive — in
     * practice the registry is always present when this service is instantiated).
     */
    fun startTimer(): Timer.Sample = Timer.start(meterRegistry)

    /**
     * Stops [sample], records the elapsed duration on the [METRIC_TOOL_CALLS_DURATION]
     * timer, increments the [METRIC_TOOL_CALLS_TOTAL] counter, and returns the elapsed
     * time in milliseconds (for attaching to [ToolResponseEvent.durationMs]).
     *
     * @param sample      The [Timer.Sample] returned by [startTimer].
     * @param toolName    Fully-qualified tool name (e.g. `FILES__read`).
     * @param agentName   Name of the agent that called the tool.
     * @param namespaceId Namespace the case belongs to.
     * @param success     Whether the tool reported a successful result.
     * @return Elapsed duration in whole milliseconds.
     */
    fun stopTimer(
        sample: Timer.Sample?,
        toolName: String,
        agentName: String,
        namespaceId: UUID,
        success: Boolean,
    ): Long {
        val tags = toolTags(toolName, agentName, namespaceId, if (success) STATUS_SUCCESS else STATUS_FAILURE)
        val timer = meterRegistry.timer(METRIC_TOOL_CALLS_DURATION, tags)
        val elapsedNanos = sample?.stop(timer) ?: 0L

        meterRegistry
            .counter(METRIC_TOOL_CALLS_TOTAL, tags)
            .increment()

        return elapsedNanos / 1_000_000L
    }

    /**
     * Records a parameter-generation failure: all retry attempts were exhausted
     * and [AgentAdvanced] fell back to `args = null`.
     *
     * @param toolName    Fully-qualified tool name.
     * @param agentName   Name of the agent.
     * @param namespaceId Namespace the case belongs to.
     */
    fun recordParameterGenerationFailure(
        toolName: String,
        agentName: String,
        namespaceId: UUID,
    ) {
        val tags =
            Tags.of(
                TAG_TOOL_NAME, toolName,
                TAG_AGENT_NAME, agentName,
                TAG_NAMESPACE_ID, namespaceId.toString(),
            )

        meterRegistry
            .counter(METRIC_TOOL_PARAM_FAILURES, tags)
            .increment()
    }

    /**
     * Records the outcome of a tool confirmation flow.
     *
     * @param toolName    Fully-qualified tool name.
     * @param agentName   Name of the agent.
     * @param namespaceId Namespace the case belongs to.
     * @param outcome     One of [ConfirmationOutcome].
     */
    fun recordConfirmation(
        toolName: String,
        agentName: String,
        namespaceId: UUID,
        outcome: ConfirmationOutcome,
    ) {
        val tags =
            Tags.of(
                TAG_TOOL_NAME, toolName,
                TAG_AGENT_NAME, agentName,
                TAG_NAMESPACE_ID, namespaceId.toString(),
                TAG_OUTCOME, outcome.tagValue,
            )

        meterRegistry
            .counter(METRIC_TOOL_CONFIRMATION_TOTAL, tags)
            .increment()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun toolTags(
        toolName: String,
        agentName: String,
        namespaceId: UUID,
        status: String,
    ): Tags =
        Tags.of(
            TAG_TOOL_NAME, toolName,
            TAG_INTEGRATION_TYPE, toolName.substringBefore("__", missingDelimiterValue = INTEGRATION_TYPE_UNKNOWN),
            TAG_AGENT_NAME, agentName,
            TAG_NAMESPACE_ID, namespaceId.toString(),
            TAG_STATUS, status,
        )

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object : KLogging() {
        const val METRIC_TOOL_CALLS_TOTAL = "agentos.tool.calls.total"
        const val METRIC_TOOL_CALLS_DURATION = "agentos.tool.calls.duration"
        const val METRIC_TOOL_PARAM_FAILURES = "agentos.tool.parameter_generation.failures"
        const val METRIC_TOOL_CONFIRMATION_TOTAL = "agentos.tool.confirmation.total"

        const val TAG_TOOL_NAME = "tool.name"
        const val TAG_INTEGRATION_TYPE = "integration.type"
        const val TAG_AGENT_NAME = "agent.name"
        const val TAG_NAMESPACE_ID = "namespace.id"
        const val TAG_STATUS = "status"
        const val TAG_OUTCOME = "outcome"

        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILURE = "failure"
        const val INTEGRATION_TYPE_UNKNOWN = "unknown"
    }
}

/**
 * Outcome of a tool confirmation flow, used as a Datadog tag value.
 */
enum class ConfirmationOutcome(
    val tagValue: String,
) {
    /** User confirmed and the tool executed successfully. */
    APPLIED("applied"),

    /** User declined (or implicit consent was absent). */
    REJECTED("rejected"),

    /** Tool threw after confirmation, or orphan pending detected. */
    ABORTED("aborted"),
}
