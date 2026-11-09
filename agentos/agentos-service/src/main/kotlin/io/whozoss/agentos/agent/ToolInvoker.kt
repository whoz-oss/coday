package io.whozoss.agentos.agent

import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KLogging
import java.util.UUID
import kotlin.time.measureTime

/**
 * Runs the tool calls of one agent run whose model drives the tool loop itself ([AgentSimple] through
 * Spring AI, [AgentClaudeCode] through its MCP bridge).
 *
 * A call emits a [ToolRequestEvent], runs the tool with a [ToolContext] that only sees the events of the
 * tool's own integration, records its metrics, then emits a [ToolResponseEvent]. A tool failure is a tool
 * result, not a run failure: it comes back as an error result so the model can correct its call. An
 * [AgentInterrupt] gets a successful response, then is rethrown for the agent to end its run.
 *
 * Calls block the calling thread, which is a Spring AI or servlet thread, never the run's coroutine.
 */
internal class ToolInvoker(
    private val agentName: String,
    private val namespaceId: UUID,
    private val caseId: UUID,
    /** AgentOS UUID of the user who initiated the case, or null when unresolvable. */
    private val userId: UUID?,
    /** Identity-provider key of the user (e.g. email). Used by plugins that manage their own auth. */
    private val userExternalId: String?,
    /** Returns the live event list of the current case at the moment of invocation. */
    private val caseEventsProvider: () -> List<CaseEvent>,
    private val toolMetricsService: ToolMetricsService?,
    /** Receives the tool events, on the thread that runs the tool. */
    private val sendEvent: (CaseEvent) -> Unit,
) {
    /**
     * Runs [tool] with [toolInput], the JSON arguments the model produced, under [toolRequestId].
     *
     * @throws AgentInterrupt after its [ToolResponseEvent], when the tool interrupts the run
     */
    fun invoke(
        tool: StandardTool<*>,
        toolInput: String,
        toolRequestId: String = UUID.randomUUID().toString(),
    ): ToolExecutionResult {
        logger.trace { "[ToolInvoker] agent=$agentName tool '${tool.name}' called with args: $toolInput" }

        sendEvent(
            ToolRequestEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequestId,
                toolName = tool.name,
                args = toolInput,
            ),
        )

        // Filter case events to only those belonging to this integration.
        // Tool names follow the "INTEGRATION_NAME__toolName" convention, so the
        // prefix before "__" is the integration identity. Events from other
        // integrations are not visible to this tool — no cross-integration leakage.
        val integrationPrefix = tool.name.substringBefore("__", missingDelimiterValue = "")
        val filteredEvents =
            caseEventsProvider().let { all ->
                if (integrationPrefix.isEmpty()) {
                    all
                } else {
                    all.filter { event ->
                        when (event) {
                            is ToolResponseEvent -> event.toolName.startsWith("${integrationPrefix}__")
                            else -> false
                        }
                    }
                }
            }
        val context =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = userExternalId,
                caseEvents = filteredEvents,
                toolRequestId = toolRequestId,
            )

        val executionResult: ToolExecutionResult
        val sample = toolMetricsService?.startTimer()
        val toolDuration =
            measureTime {
                executionResult =
                    try {
                        // MDCContext() captures the MDC of this thread, which the caller has set to the run's keys
                        // plus the foreign keys (e.g. an infra trace id) of the calling thread.
                        runBlocking(Dispatchers.IO + MDCContext()) {
                            tool.executeWithJson(toolInput, context)
                        }
                    } catch (e: AgentInterrupt) {
                        // Interrupt is not an error: emit a successful response so traces
                        // are complete, then re-throw the signal for the agent to end its run.
                        toolMetricsService?.stopTimerAndSendMetrics(
                            sample,
                            tool.name,
                            agentName,
                            namespaceId,
                            success = true,
                        )
                        sendEvent(
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                output = MessageContent.Text(e.toolOutput()),
                                success = true,
                                structuredOutput = (e as? AgentInterrupt.AwaitAnswer)?.structuredOutput,
                            ),
                        )
                        throw e
                    } catch (e: Exception) {
                        // Cancellation and thread interruption (runBlocking above) are signals,
                        // not tool failures: keep unwinding the run instead of turning them
                        // into a result the LLM would be re-prompted on.
                        if (e is CancellationException || e is InterruptedException) throw e
                        // A tool failure is a tool result, not a run failure: the message goes
                        // back to the LLM so it can correct its call (same contract as
                        // AgentAdvanced.executeTool). Spring AI only turns ToolExecutionException
                        // into a tool result; any other exception escaping call() errors the
                        // stream and ends the turn.
                        logger.warn(e) { "[ToolInvoker] agent=$agentName error during tool execution for ${tool.name}" }
                        val reason = e.message ?: e::class.simpleName ?: "unknown error"
                        ToolExecutionResult.error(
                            "Error executing tool: $reason",
                            errorType = e::class.simpleName,
                            errorMessage = e.message,
                        )
                    }
            }
        // Success and tool-error paths stop the timer here. AgentInterrupt stops it before
        // rethrowing; cancellation/interruption rethrow without recording a metric.
        toolMetricsService?.stopTimerAndSendMetrics(
            sample,
            tool.name,
            agentName,
            namespaceId,
            success = executionResult.success,
        )

        sendEvent(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequestId,
                toolName = tool.name,
                output = MessageContent.Text(executionResult.output),
                success = executionResult.success,
                durationMs = toolDuration.inWholeMilliseconds,
                toolMetadata = executionResult.metadata,
                // Images are preserved on the event (persistence, SSE).
                images = executionResult.images,
                structuredOutput = executionResult.structuredOutput,
            ),
        )
        return executionResult
    }

    companion object : KLogging()
}

/** The tool result recorded for a call that interrupted the run. */
internal fun AgentInterrupt.toolOutput(): String =
    when (this) {
        is AgentInterrupt.Redirect -> "Redirecting to agent '$targetAgentName'."
        is AgentInterrupt.AwaitAnswer -> "Waiting for the user's answer."
    }
