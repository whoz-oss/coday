package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode

/**
 * What the [io.whozoss.agentos.agent.AgentLoop] executes for one run: an ordered list of fully
 * resolved tool calls. It is the *runtime* form of a [Workflow], produced by a [LoopConfigResolver]
 * that has already turned runtime values into concrete inputs. The loop runtime knows nothing of
 * scheduled prompts or targets.
 *
 * @param steps Executed strictly in order, no branching.
 */
data class LoopConfig(
    val steps: List<LoopStep>,
)

/**
 * One resolved step.
 *
 * @param id Step id, echoed in the case transcript and in error messages.
 * @param tool Tool name as granted to the loop agent, bare or `CONFIG__tool`.
 * @param input Tool input, a JSON object.
 * @param feedsNext How this step's structured output reaches the next step: when non-null, the
 *   value of this field of the output is copied, under the same name, into the next step's input.
 *   For scheduler workflows it is [WorkflowTargets.FIELD].
 */
data class LoopStep(
    val id: String,
    val tool: String,
    val input: JsonNode,
    val feedsNext: String? = null,
)
