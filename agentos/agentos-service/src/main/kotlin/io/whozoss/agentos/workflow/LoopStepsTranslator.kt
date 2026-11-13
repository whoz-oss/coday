package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.TextNode
import io.whozoss.agentos.casePlugin.FreeCaseStarterForUserTool
import java.util.UUID

/**
 * Turns the `steps` format of a `loopConfig` ([LoopSteps]) and the `loopData` of a run into the
 * [AgentLoopPayload] that [LoopWorkflowRunnerService] executes.
 *
 * The placeholders of the steps are resolved against `loopData` first ([LoopPlaceholders]), then the result is
 * checked. V1 rules, all reported as an [IllegalArgumentException] naming the step, so that nothing runs when a
 * loop is misconfigured:
 *
 * - exactly two steps, a [LoopStepType.SEARCH] then a [LoopStepType.START_CASES], with distinct non-blank ids;
 * - every `tool` resolves to a non-blank string; the one of the START_CASES step designates a
 *   `FreeCaseStarterForUser` tool (bare, or after its `CONFIG__` prefix), because the runner builds that tool's
 *   input itself;
 * - the SEARCH `args` resolve to an object (absent or null means `{}`) and are handed to the search tool as is;
 * - the START_CASES `args` resolve to an object holding `agentName` and `prompt` (non-blank strings) and
 *   optionally a `title` (string). `userId` and `sessionContext` are reserved for the runner, which fills them
 *   per search hit; any other key is refused;
 * - `scheduledPromptId` belongs to the START_CASES step, and resolves to a UUID when present.
 */
object LoopStepsTranslator {
    private val START_CASES_ARGS = setOf("agentName", "prompt", "title")
    private val RESERVED_ARGS = setOf("userId", "sessionContext")

    /**
     * @param loopData the initial data of the run, a JSON object.
     * @throws IllegalArgumentException when the steps are invalid, or a placeholder cannot be resolved.
     */
    fun translate(
        config: LoopSteps,
        loopData: JsonNode,
    ): AgentLoopPayload {
        val (search, startCases) = requireSearchThenStartCases(config.steps)

        val searchTool = resolveTool(search, loopData)
        val searchArgs = resolveSearchArgs(search, loopData)
        require(search.scheduledPromptId == null) {
            "${locate(search)}: 'scheduledPromptId' only applies to the ${LoopStepType.START_CASES} step"
        }

        val startTool = resolveTool(startCases, loopData)
        require(FreeCaseStarterForUserTool.isFreeCaseStarterForUserTool(startTool)) {
            "${locate(startCases)}: tool '$startTool' must be the '${FreeCaseStarterForUserTool.TOOL_NAME}' tool " +
                "(bare or after its 'CONFIG__' prefix): the loop builds its input"
        }
        val act = resolveStartCasesArgs(startCases, loopData)

        return AgentLoopPayload(
            search = AgentLoopSearch(tool = searchTool, params = searchArgs),
            act =
                AgentLoopAct(
                    agentName = act.agentName,
                    prompt = act.prompt,
                    title = act.title,
                    tool = startTool,
                    scheduledPromptId = resolveScheduledPromptId(startCases, loopData),
                ),
        )
    }

    private fun requireSearchThenStartCases(steps: List<LoopStepConfig>): Pair<LoopStepConfig, LoopStepConfig> {
        require(steps.size == 2 && steps[0].type == LoopStepType.SEARCH && steps[1].type == LoopStepType.START_CASES) {
            "a loop must have exactly two steps, a ${LoopStepType.SEARCH} then a ${LoopStepType.START_CASES} " +
                "(found: ${steps.joinToString { "'${it.id}' ${it.type}" }.ifEmpty { "none" }})"
        }
        require(steps.none { it.id.isBlank() }) { "every step needs a non-blank id" }
        require(steps[0].id != steps[1].id) { "step ids must be distinct (found '${steps[0].id}' twice)" }
        return steps[0] to steps[1]
    }

    private fun resolveTool(
        step: LoopStepConfig,
        loopData: JsonNode,
    ): String {
        val resolved = LoopPlaceholders.resolve(TextNode.valueOf(step.tool), loopData, "${locate(step)} tool")
        require(resolved.isTextual && resolved.textValue().isNotBlank()) {
            "${locate(step)}: 'tool' must resolve to a non-blank string, got ${resolved}"
        }
        return resolved.textValue()
    }

    private fun resolveSearchArgs(
        step: LoopStepConfig,
        loopData: JsonNode,
    ): JsonNode {
        val resolved = step.args?.let { LoopPlaceholders.resolve(it, loopData, "${locate(step)} args") }
        return when {
            resolved == null || resolved.isNull -> JsonNodeFactory.instance.objectNode()
            resolved.isObject -> resolved
            else -> throw IllegalArgumentException("${locate(step)}: 'args' must resolve to an object, got $resolved")
        }
    }

    private data class StartCasesArgs(
        val agentName: String,
        val prompt: String,
        val title: String?,
    )

    private fun resolveStartCasesArgs(
        step: LoopStepConfig,
        loopData: JsonNode,
    ): StartCasesArgs {
        val template = requireNotNull(step.args) { "${locate(step)}: 'args' is required, with 'agentName' and 'prompt'" }
        val args = LoopPlaceholders.resolve(template, loopData, "${locate(step)} args")
        require(args.isObject) { "${locate(step)}: 'args' must resolve to an object, got $args" }

        args.fieldNames().forEach { name ->
            require(name !in RESERVED_ARGS) {
                "${locate(step)}: argument '$name' is reserved, the loop sets it for each search hit"
            }
            require(name in START_CASES_ARGS) {
                "${locate(step)}: unknown argument '$name' (allowed: ${START_CASES_ARGS.joinToString()})"
            }
        }
        return StartCasesArgs(
            agentName = requiredText(step, args, "agentName"),
            prompt = requiredText(step, args, "prompt"),
            title = optionalText(step, args, "title"),
        )
    }

    private fun requiredText(
        step: LoopStepConfig,
        args: JsonNode,
        name: String,
    ): String {
        val value = args.get(name)
        require(value != null && value.isTextual && value.textValue().isNotBlank()) {
            "${locate(step)}: argument '$name' is required and must be a non-blank string"
        }
        return value.textValue()
    }

    private fun optionalText(
        step: LoopStepConfig,
        args: JsonNode,
        name: String,
    ): String? {
        val value = args.get(name)
        require(value == null || value.isNull || value.isTextual) {
            "${locate(step)}: argument '$name' must be a string, got $value"
        }
        return value?.takeIf { it.isTextual }?.textValue()
    }

    private fun resolveScheduledPromptId(
        step: LoopStepConfig,
        loopData: JsonNode,
    ): UUID? =
        step.scheduledPromptId?.let { template ->
            val resolved = LoopPlaceholders.resolve(TextNode.valueOf(template), loopData, "${locate(step)} scheduledPromptId")
            val id = if (resolved.isTextual) runCatching { UUID.fromString(resolved.textValue()) }.getOrNull() else null
            requireNotNull(id) { "${locate(step)}: 'scheduledPromptId' must resolve to a UUID, got $resolved" }
        }

    private fun locate(step: LoopStepConfig) = "step '${step.id}'"
}
