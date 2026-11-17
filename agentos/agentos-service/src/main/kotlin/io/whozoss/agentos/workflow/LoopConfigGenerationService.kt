package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.casePlugin.FreeCaseStarterForUserTool
import io.whozoss.agentos.casePlugin.FreeCaseStarterTool
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.api.agentConfig.LoopConfigGenerationResult
import io.whozoss.agentos.sdk.tool.StandardTool
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Translates a natural-language description into the **search block** of a loop configuration:
 * `{ "search": { "tool": "...", "params": { ... } } }`.
 *
 * The output is **not** a complete loop configuration (an [AgentLoopPayload]): the `act` block, which names the agent
 * started for each search result and its prompt, is not generated here. The caller completes it, for example with
 * the `flowConfiguration` of a scheduled prompt, where the scheduler injects `act.prompt`.
 *
 * ## How it works
 *
 * Two modes depending on whether [generate]'s `searchToolName` is provided:
 *
 * **Single-tool mode** (`searchToolName` non-null): the LLM produces the params of that tool, from its input schema.
 * The tool is found with [lookupTool], the rule the loop itself applies at run time: its exact name, else its name
 * behind an integration prefix (`searchAgentUsers` finds `USER_GROUP__searchAgentUsers`). Several matches are refused
 * as ambiguous. The search block names the tool by its real name, so that the loop finds the same one.
 *
 * **Auto-select mode** (`searchToolName` null): the LLM picks a tool among the agent's search candidates and produces
 * `{ "tool": "...", "params": { ... } }`; the tool must be one of them.
 *
 * In both modes the case starters (`FreeCaseStarter`, `FreeCaseStarterForUser`) are not search candidates: a LOOP
 * agent always has one, since it starts a case per result, and the loop would otherwise call it as its search.
 *
 * In both modes an optional `context` is appended to the user message as an "Additional context" block, to hand the
 * LLM identifiers it can use as parameter values (for instance the id of the agent started for each hit).
 *
 * ## Base parameters
 *
 * An optional `baseParams` object is a starting point the generation only **enriches**, typically by adding criteria.
 * The LLM is shown it and told to keep it, and the rule does not rest on the LLM alone: the base is merged back over
 * its answer ([enrich]), so that every base value survives as given, nested objects included, and the LLM can only
 * add keys. In auto-select mode, the base parameters are those of whichever tool the LLM picks: pass a tool name
 * with them when they belong to a given tool.
 *
 * The LLM call and the parse of its answer are those of [StructuredLlmClient].
 *
 * ## Error handling
 *
 * Whatever stops the generation is explained in [LoopConfigGenerationResult.warning], and
 * [LoopConfigGenerationResult.loopConfig] is null; [LoopConfigGenerationResult.rawResponse] only ever carries the raw
 * LLM text, and only when the answer could not be used.
 *
 * - Unknown or ambiguous `searchToolName`, or no search candidate (auto-select mode): no LLM call.
 * - The LLM returns nothing, returns no JSON object, or (auto-select mode) names no tool or an unknown one.
 * - LLM throws: re-thrown as-is.
 */
@Service
class LoopConfigGenerationService(
    private val structuredLlmClient: StructuredLlmClient,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Generate the search block from [description] using the LLM resolved from [definition].
     *
     * @param definition  Fully-resolved agent definition (model, provider, tools).
     * @param searchToolName  Name of the search tool to look up in [ResolvedAgentDefinition.tools], or null to let the
     *   LLM choose among them.
     * @param description  Natural-language description of the desired search criteria.
     * @param context  Optional extra information appended to the user message, such as ids the LLM may use in params.
     * @param baseParams  Optional params object to start from: kept as given, only enriched (see the class KDoc).
     * @return [LoopConfigGenerationResult] with the generated search block, or a null one explained by its warning.
     */
    suspend fun generate(
        definition: ResolvedAgentDefinition,
        searchToolName: String?,
        description: String,
        context: String? = null,
        baseParams: JsonNode? = null,
    ): LoopConfigGenerationResult {
        val base = baseParams?.takeIf { it.isObject && it.size() > 0 }
        return when (searchToolName) {
            null -> generateAutoSelect(definition, description, context, base)
            else -> generateSingleTool(definition, searchToolName, description, context, base)
        }
    }

    // -------------------------------------------------------------------------
    // Single-tool mode
    // -------------------------------------------------------------------------

    private suspend fun generateSingleTool(
        definition: ResolvedAgentDefinition,
        searchToolName: String,
        description: String,
        context: String?,
        baseParams: JsonNode?,
    ): LoopConfigGenerationResult {
        val candidates = searchCandidates(definition)
        val matches = lookupTool(candidates, searchToolName)
        val tool = matches.singleOrNull()
        return when {
            tool != null -> {
                val userMessage = userMessage(schemaBlock(tool), description, context, baseParams)
                val llmResult =
                    structuredLlmClient.call(
                        model = definition.resolvedModel,
                        provider = definition.resolvedProvider,
                        systemPrompt = SINGLE_TOOL_SYSTEM_PROMPT,
                        userMessage = userMessage,
                    )
                paramsResult(llmResult, tool.name, baseParams)
            }

            matches.isEmpty() -> {
                val available = candidates.map { it.name }
                logger.warn {
                    "[LoopConfigGeneration] Tool '$searchToolName' not found for agent '${definition.name}' (available: $available)"
                }
                LoopConfigGenerationResult(
                    loopConfig = null,
                    warning = "Tool '$searchToolName' is not available to agent '${definition.name}'. Available tools: $available",
                )
            }

            else -> {
                val names = matches.map { it.name }
                logger.warn { "[LoopConfigGeneration] Tool '$searchToolName' is ambiguous for agent '${definition.name}': $names" }
                LoopConfigGenerationResult(
                    loopConfig = null,
                    warning = "Tool '$searchToolName' is ambiguous for agent '${definition.name}': $names. Use its prefixed name.",
                )
            }
        }
    }

    /** Single-tool mode: the answer is the params object itself, enriching [baseParams], wrapped in the search block. */
    private fun paramsResult(
        llmResult: StructuredLlmResult,
        searchToolName: String,
        baseParams: JsonNode?,
    ): LoopConfigGenerationResult {
        val generated = llmResult.json
        return when (generated) {
            null -> unusable(llmResult)

            else -> {
                val params = enrich(baseParams, generated)
                val warning =
                    listOfNotNull(
                        FENCE_WARNING.takeIf { llmResult.usedCodeFence },
                        EMPTY_OBJECT_WARNING.takeIf { params.size() == 0 },
                    ).joinToString(" ").takeIf { it.isNotEmpty() }
                LoopConfigGenerationResult(loopConfig = searchBlock(searchToolName, params), warning = warning)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto-select mode
    // -------------------------------------------------------------------------

    private suspend fun generateAutoSelect(
        definition: ResolvedAgentDefinition,
        description: String,
        context: String?,
        baseParams: JsonNode?,
    ): LoopConfigGenerationResult {
        val tools = searchCandidates(definition)
        return when {
            tools.isEmpty() -> {
                logger.warn { "[LoopConfigGeneration] No search tool available for agent '${definition.name}' (auto-select mode)" }
                LoopConfigGenerationResult(
                    loopConfig = null,
                    warning = "No search tools are available to agent '${definition.name}'."
                )
            }

            else -> {
                val toolsBlock = tools.joinToString("\n\n") { schemaBlock(it) }
                val userMessage = userMessage("Available tools:\n$toolsBlock", description, context, baseParams)
                logger.debug { "[LoopConfigGeneration] Auto-selecting among ${tools.size} tools (message length=${userMessage.length})" }
                val llmResult =
                    structuredLlmClient.call(
                        definition.resolvedModel,
                        definition.resolvedProvider,
                        AUTO_SELECT_SYSTEM_PROMPT,
                        userMessage
                    )
                autoSelectResult(llmResult, tools.map { it.name }, baseParams)
            }
        }
    }

    /**
     * Auto-select mode: the answer is `{ "tool": "...", "params": { ... } }`, wrapped in the search block.
     * The chosen tool must be among [knownToolNames].
     */
    private fun autoSelectResult(
        llmResult: StructuredLlmResult,
        knownToolNames: List<String>,
        baseParams: JsonNode?,
    ): LoopConfigGenerationResult {
        val node = llmResult.json
        val chosenTool = node?.get("tool")?.asText()?.takeIf { it.isNotBlank() }
        return when {
            node == null -> {
                unusable(llmResult)
            }

            chosenTool == null -> {
                logger.warn { "[LoopConfigGeneration] Auto-select response missing 'tool' field" }
                LoopConfigGenerationResult(
                    loopConfig = null,
                    rawResponse = llmResult.rawText,
                    warning = "The LLM response did not include a 'tool' field.",
                )
            }

            chosenTool !in knownToolNames -> {
                logger.warn { "[LoopConfigGeneration] Auto-select chose unknown tool '$chosenTool' (known: $knownToolNames)" }
                LoopConfigGenerationResult(
                    loopConfig = null,
                    rawResponse = llmResult.rawText,
                    warning = "The LLM chose tool '$chosenTool' which is not available to this agent.",
                )
            }

            else -> {
                logger.info { "[LoopConfigGeneration] Auto-select chose tool '$chosenTool'" }
                val generated = node.get("params")?.takeUnless { it.isNull } ?: objectMapper.createObjectNode()
                LoopConfigGenerationResult(
                    loopConfig = searchBlock(chosenTool, enrich(baseParams, generated)),
                    warning = FENCE_WARNING.takeIf { llmResult.usedCodeFence },
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The agent's tools that can serve as the loop's search: all but the case starters. */
    private fun searchCandidates(definition: ResolvedAgentDefinition): List<StandardTool<*>> =
        definition.tools.filterNot { tool -> NON_SEARCH_TOOLS.any { lookupTool(listOf(tool), it).isNotEmpty() } }

    private fun schemaBlock(tool: StandardTool<*>): String =
        "Tool name: ${tool.name}\nTool input schema:\n${tool.inputSchema}"

    /**
     * The user message: [header], the description, the "Base parameters" block when there are [baseParams], and the
     * "Additional context" block when there is a [context].
     */
    private fun userMessage(
        header: String,
        description: String,
        context: String?,
        baseParams: JsonNode?,
    ): String =
        buildString {
            appendLine(header)
            appendLine()
            appendLine("User description:")
            append(description)
            baseParams?.let {
                appendLine()
                appendLine()
                appendLine("Base parameters (keep every one as given, only add what the description asks for):")
                append(objectMapper.writeValueAsString(it))
            }
            context?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine()
                appendLine("Additional context:")
                append(it)
            }
        }

    /**
     * [generated] enriching [base]: every value of [base] is kept as given, the LLM only adds keys. Nested objects
     * present on both sides are enriched the same way; any other value of [base] wins as a whole (an array of
     * criteria is not merged item by item). Without a [base], [generated] as is.
     */
    internal fun enrich(
        base: JsonNode?,
        generated: JsonNode,
    ): JsonNode =
        when {
            base == null || !base.isObject || !generated.isObject -> {
                base?.deepCopy<JsonNode>() ?: generated
            }

            else -> {
                (generated.deepCopy<JsonNode>() as ObjectNode).also { merged ->
                    base.fields().forEach { (key, baseValue) ->
                        merged.set<JsonNode>(key, enrich(baseValue, merged.get(key) ?: baseValue))
                    }
                }
            }
        }

    /** The LLM answered nothing, or no JSON object: nothing to build, the warning says why. */
    private fun unusable(llmResult: StructuredLlmResult): LoopConfigGenerationResult =
        when {
            llmResult.returnedNothing -> {
                LoopConfigGenerationResult(loopConfig = null, warning = "The LLM returned no content.")
            }

            else -> {
                LoopConfigGenerationResult(
                    loopConfig = null,
                    rawResponse = llmResult.rawText,
                    warning = "The LLM response could not be parsed as a JSON object.",
                )
            }
        }

    /** `{ "search": { "tool": "SearchTasks", "params": { ... } } }` */
    private fun searchBlock(
        searchToolName: String,
        params: JsonNode,
    ): JsonNode =
        objectMapper.createObjectNode().apply {
            putObject("search").apply {
                put("tool", searchToolName)
                set<JsonNode>("params", params)
            }
        }

    companion object : KLogging() {
        /** Tools a LOOP agent has to start the cases of its results, never to search with. */
        private val NON_SEARCH_TOOLS = listOf(FreeCaseStarterTool.TOOL_NAME, FreeCaseStarterForUserTool.TOOL_NAME)

        private const val FENCE_WARNING = "JSON was extracted from a markdown code fence in the LLM response."
        private const val EMPTY_OBJECT_WARNING =
            "The LLM returned an empty JSON object. The description may be too vague."

        /** System prompt for single-tool mode: the tool is known, only the params are generated. */
        private val SINGLE_TOOL_SYSTEM_PROMPT =
            """
            You are a JSON parameter generator for a search tool.

            Given:
            - the tool's JSON Schema (its accepted input)
            - a natural-language description of what the user wants to search for
            - optionally, a "Base parameters" block: parameters to start from
            - optionally, an "Additional context" block that may provide ids to use in params

            Produce ONLY a valid JSON object that:
            - matches the tool's input schema
            - captures the intent expressed in the description
            - uses the ids given in the additional context when the schema asks for them
            - uses null for optional fields that are not mentioned
            - does NOT invent fields that are not in the schema
            - when "Base parameters" are given: keeps every one of them with its value unchanged, and only adds the
              parameters the description asks for (typically extra criteria)

            Output the raw JSON object with no explanation, no markdown, no code fences.
            Start your response with '{' and end it with '}'.
            """.trimIndent()

        /**
         * System prompt for auto-select mode: the LLM must also choose the right tool, and returns
         * `{ "tool": "<toolName>", "params": { ... } }`.
         */
        private val AUTO_SELECT_SYSTEM_PROMPT =
            """
            You are a JSON parameter generator for search tools.

            Given:
            - a list of available tools, each with its name and JSON Schema
            - a natural-language description of what the user wants to search for
            - optionally, a "Base parameters" block: parameters to start from
            - optionally, an "Additional context" block that may provide ids to use in params

            Select the most appropriate tool and produce ONLY a JSON object in this exact format:
            {
              "tool": "<chosen tool name>",
              "params": { <parameters matching the chosen tool's schema> }
            }

            Rules:
            - Choose the tool whose schema best fits the user's description.
            - params must match the chosen tool's input schema exactly.
            - Use the ids given in the additional context when the chosen tool's schema asks for them.
            - Use null for optional fields that are not mentioned.
            - Do NOT invent fields that are not in the schema.
            - Do NOT add explanation, markdown, or code fences.
            - When "Base parameters" are given: keep every one of them with its value unchanged in params, and only add
              the parameters the description asks for (typically extra criteria).
            - Start your response with '{' and end it with '}'.
            """.trimIndent()
    }
}
