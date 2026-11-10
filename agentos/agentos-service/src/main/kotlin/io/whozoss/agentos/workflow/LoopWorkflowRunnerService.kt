package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
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
 * Executes a resolved [LoopConfig] inside an [io.whozoss.agentos.agent.AgentLoop] case.
 *
 * For each step, in order:
 * 1. stop with [LoopRunOutcome.Interrupted] when [shouldContinue] is false;
 * 2. find the tool among the loop agent's tools (exact name or `CONFIG__name`);
 * 3. build its input: [LoopStep.input], plus the field handed over by the previous step
 *    ([LoopStep.feedsNext]);
 * 4. emit a [ToolRequestEvent], run the tool, emit a [ToolResponseEvent] (the case transcript);
 * 5. stop with [LoopRunOutcome.Aborted] and a readable reason on any failure.
 *
 * It has no knowledge of scheduled prompts, workflows or targets: that is the resolver's job.
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

        var handedOver: Pair<String, JsonNode>? = null
        var lastStepOutput: String? = null

        for (step in config.steps) {
            if (!shouldContinue()) return LoopRunOutcome.Interrupted

            val tool =
                context.tools.find { it.name == step.tool || it.name.endsWith("__" + step.tool) }
                    ?: return LoopRunOutcome.Aborted(
                        "Step '${step.id}': tool '${step.tool}' is not available to agent '${context.agentName}' " +
                            "(available: ${context.tools.map { it.name }}). Check the agent integrations.",
                    )

            val input =
                (step.input as? ObjectNode)?.deepCopy()
                    ?: return LoopRunOutcome.Aborted("Step '${step.id}': input must be a JSON object.")
            handedOver?.let { (field, value) -> input.set<JsonNode>(field, value) }

            val inputJson = objectMapper.writeValueAsString(input)
            val toolRequestId = UUID.randomUUID().toString()
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
                    tool.executeWithJson(inputJson, toolContext(context, triggerUser))
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
                return LoopRunOutcome.Aborted("Step '${step.id}': tool '${tool.name}' returned a failure: ${result.output}")
            }

            handedOver =
                step.feedsNext?.let { field ->
                    val value =
                        result.structuredOutput?.get(field)
                            ?: return LoopRunOutcome.Aborted(
                                "Step '${step.id}': tool '${tool.name}' output has no '$field' field to hand to the next step.",
                            )
                    field to value
                }
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
