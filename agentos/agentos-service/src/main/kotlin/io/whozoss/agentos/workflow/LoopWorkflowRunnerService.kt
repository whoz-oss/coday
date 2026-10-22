package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.User
import kotlinx.coroutines.CancellationException
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Executes the config-driven step workflow of an [io.whozoss.agentos.agent.AgentLoop] run.
 *
 * Each step names a tool and an optional input descriptor. Steps are executed strictly in
 * order; the output of one step can be forwarded to the next via [LoopStepInput.from].
 *
 * ## Flow
 *
 * 1. Guard: the triggering user must be identified.
 * 2. For each step in [LoopConfig.steps]:
 *    a. Check [shouldContinue]; return [LoopRunOutcome.Interrupted] if false.
 *    b. Resolve the tool by name (exact or `__`-suffix match).
 *    c. Build the input JSON from [LoopStepInput.params] and optionally a prior step output.
 *    d. Emit [ToolRequestEvent], execute the tool, emit [ToolResponseEvent].
 *    e. Return [LoopRunOutcome.Aborted] on tool failure; store structured output for later steps.
 * 3. Return [LoopRunOutcome.Completed] with the last step’s output.
 */
@Service
class LoopWorkflowRunnerService(
    private val objectMapper: ObjectMapper,
) {
    suspend fun run(
        config: LoopConfig,
        context: LoopRunContext,
        shouldContinue: () -> Boolean,
    ): LoopRunOutcome {
        val triggerUser =
            context.triggerUser
                ?: return LoopRunOutcome.Aborted("An AgentLoop must be triggered by an identified user.")

        // Stores structuredOutput (as JsonNode) keyed by step id, for use by later steps via `from`.
        val stepOutputs = mutableMapOf<String, com.fasterxml.jackson.databind.JsonNode>()
        var lastStepOutput: String? = null

        for (step in config.steps) {
            if (!shouldContinue()) return LoopRunOutcome.Interrupted

            // Resolve tool by exact name or by the part after "__".
            val tool =
                context.tools.find { it.name == step.tool || it.name.endsWith("__" + step.tool) }
                    ?: return LoopRunOutcome.Aborted(
                        "Step '${step.id}': tool '${step.tool}' is not available to agent '${context.agentName}' " +
                            "(available: ${context.tools.map { it.name }}). Check the agent integrations.",
                    )

            // Build the input JSON node.
            val inputNode: ObjectNode =
                step.input.params?.deepCopy()
                    ?: objectMapper.createObjectNode()

            if (step.input.from != null) {
                val prior =
                    stepOutputs[step.input.from]
                        ?: return LoopRunOutcome.Aborted(
                            "Step '${step.id}': references output of step '${step.input.from}' via 'from', " +
                                "but that step has no structured output or was not executed yet.",
                        )
                inputNode.set<com.fasterxml.jackson.databind.JsonNode>("previousStepOutput", prior)
            }

            val inputJson = objectMapper.writeValueAsString(inputNode)
            val toolRequestId = UUID.randomUUID().toString()
            val toolCtx = toolContext(context, triggerUser)

            context.emitEvent(
                ToolRequestEvent(
                    namespaceId = context.namespaceId,
                    caseId = context.caseId,
                    toolRequestId = toolRequestId,
                    toolName = tool.name,
                    args = inputJson,
                ),
            )

            val result =
                try {
                    tool.executeWithJson(inputJson, toolCtx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "[LoopWorkflowRunner] Step '${step.id}' tool '${tool.name}' threw (caseId=${context.caseId})" }
                    return LoopRunOutcome.Aborted("Step '${step.id}': tool '${tool.name}' failed: ${e.message}")
                }

            context.emitEvent(
                ToolResponseEvent(
                    namespaceId = context.namespaceId,
                    caseId = context.caseId,
                    toolRequestId = toolRequestId,
                    toolName = tool.name,
                    output = MessageContent.Text(result.output),
                    success = result.success,
                    toolMetadata = result.metadata,
                    structuredOutput = result.structuredOutput,
                ),
            )

            if (!result.success) {
                return LoopRunOutcome.Aborted(
                    "Step '${step.id}': tool '${tool.name}' returned a failure: ${result.output}",
                )
            }

            result.structuredOutput?.let { stepOutputs[step.id] = it }
            lastStepOutput = result.output
        }

        return LoopRunOutcome.Completed(lastStepOutput = lastStepOutput)
    }

    private fun toolContext(
        context: LoopRunContext,
        triggerUser: User,
    ) = ToolContext(
        namespaceId = context.namespaceId,
        userId = triggerUser.metadata.id,
        userExternalId = triggerUser.externalId,
        caseEvents = context.caseEvents,
        agentName = context.agentName,
    )

    companion object : KLogging()
}
