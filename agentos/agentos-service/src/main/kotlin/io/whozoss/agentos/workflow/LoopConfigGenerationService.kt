package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.api.agentConfig.LoopConfigGenerationResult
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
 * **Single-tool mode** (`searchToolName` non-null):
 * 1. Look up the named tool in [ResolvedAgentDefinition.tools] and read its input schema.
 * 2. Instruct the LLM to produce a JSON object matching that schema.
 * 3. Wrap the response in `{ "search": { "tool": "...", "params": ... } }`.
 *
 * **Auto-select mode** (`searchToolName` null):
 * 1. Collect all available tools and their schemas.
 * 2. Instruct the LLM to pick the best tool and produce `{ "tool": "...", "params": { ... } }`.
 * 3. Validate that `tool` names a known tool, and wrap in the `{ "search": { ... } }` structure.
 *
 * In both modes an optional `context` is appended to the user message as an "Additional context" block, to hand the
 * LLM identifiers it can use as parameter values (for instance the id of the agent started for each hit).
 *
 * The LLM call and the parse of its answer are those of [StructuredLlmClient].
 *
 * ## Error handling
 *
 * Whatever stops the generation is explained in [LoopConfigGenerationResult.warning], and
 * [LoopConfigGenerationResult.loopConfig] is null; [LoopConfigGenerationResult.rawResponse] only ever carries the raw
 * LLM text, and only when the answer could not be used.
 *
 * - Unknown `searchToolName`, or no tool available (auto-select mode): no LLM call.
 * - The LLM returns nothing, returns no JSON object, or (auto-select mode) names no tool or an unknown one.
 * - LLM throws: re-thrown as-is. The controller maps provider errors to HTTP 502.
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
     * @return [LoopConfigGenerationResult] with the generated search block, or a null one explained by its warning.
     */
    fun generate(
        definition: ResolvedAgentDefinition,
        searchToolName: String?,
        description: String,
        context: String? = null,
    ): LoopConfigGenerationResult =
        when (searchToolName) {
            null -> generateAutoSelect(definition, description, context)
            else -> generateSingleTool(definition, searchToolName, description, context)
        }

    // -------------------------------------------------------------------------
    // Single-tool mode
    // -------------------------------------------------------------------------

    private fun generateSingleTool(
        definition: ResolvedAgentDefinition,
        searchToolName: String,
        description: String,
        context: String?,
    ): LoopConfigGenerationResult {
        val tool = definition.tools.find { it.name == searchToolName }
        if (tool == null) {
            val available = definition.tools.map { it.name }
            logger.warn {
                "[LoopConfigGeneration] Tool '$searchToolName' not found for agent '${definition.name}' " +
                    "(available: $available)"
            }
            return LoopConfigGenerationResult(
                loopConfig = null,
                warning = "Tool '$searchToolName' is not available to agent '${definition.name}'. Available tools: $available",
            )
        }

        logger.debug {
            "[LoopConfigGeneration] Generating params for tool '$searchToolName' " +
                "(agent=${definition.name}, schema length=${tool.inputSchema.length})"
        }

        val userMessage =
            userMessage(
                header = "Tool name: $searchToolName\nTool input schema:\n${tool.inputSchema}",
                description = description,
                context = context,
            )
        val llmResult =
            structuredLlmClient.call(definition.resolvedModel, definition.resolvedProvider, SINGLE_TOOL_SYSTEM_PROMPT, userMessage)
        return parseParamsResult(llmResult, searchToolName)
    }

    // -------------------------------------------------------------------------
    // Auto-select mode
    // -------------------------------------------------------------------------

    private fun generateAutoSelect(
        definition: ResolvedAgentDefinition,
        description: String,
        context: String?,
    ): LoopConfigGenerationResult {
        val tools = definition.tools.toList()
        if (tools.isEmpty()) {
            logger.warn { "[LoopConfigGeneration] No tools available for agent '${definition.name}' (auto-select mode)" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                warning = "No tools are available to agent '${definition.name}'.",
            )
        }

        val toolsBlock =
            tools.joinToString("\n\n") { tool ->
                "Tool name: ${tool.name}\nTool input schema:\n${tool.inputSchema}"
            }

        logger.debug {
            "[LoopConfigGeneration] Auto-selecting tool for agent '${definition.name}' " +
                "from ${tools.size} available tools"
        }

        val userMessage = userMessage(header = "Available tools:\n$toolsBlock", description = description, context = context)
        val llmResult =
            structuredLlmClient.call(definition.resolvedModel, definition.resolvedProvider, AUTO_SELECT_SYSTEM_PROMPT, userMessage)
        return parseAutoSelectResult(llmResult, tools.map { it.name })
    }

    // -------------------------------------------------------------------------
    // Message and result helpers
    // -------------------------------------------------------------------------

    /** The user message: [header], the description, and the "Additional context" block when there is a [context]. */
    private fun userMessage(
        header: String,
        description: String,
        context: String?,
    ): String =
        buildString {
            appendLine(header)
            appendLine()
            appendLine("User description:")
            append(description)
            context?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine()
                appendLine("Additional context:")
                append(it)
            }
        }

    /** The LLM answered nothing, or no JSON object: nothing to build, the warning says why. */
    private fun unusable(llmResult: StructuredLlmResult): LoopConfigGenerationResult =
        when {
            llmResult.returnedNothing -> LoopConfigGenerationResult(loopConfig = null, warning = "The LLM returned no content.")
            else ->
                LoopConfigGenerationResult(
                    loopConfig = null,
                    rawResponse = llmResult.rawText,
                    warning = "The LLM response could not be parsed as a JSON object.",
                )
        }

    /**
     * Single-tool mode: the answer is the params object itself, wrapped in the search block.
     */
    private fun parseParamsResult(
        llmResult: StructuredLlmResult,
        searchToolName: String,
    ): LoopConfigGenerationResult {
        val params = llmResult.json ?: return unusable(llmResult)
        if (params.size() == 0) {
            logger.warn { "[LoopConfigGeneration] LLM returned an empty JSON object for tool '$searchToolName'" }
        }
        val warning =
            listOfNotNull(
                FENCE_WARNING.takeIf { llmResult.usedCodeFence },
                EMPTY_OBJECT_WARNING.takeIf { params.size() == 0 },
            ).joinToString(" ").takeIf { it.isNotEmpty() }
        return LoopConfigGenerationResult(loopConfig = wrapInSearchBlock(searchToolName, params), warning = warning)
    }

    /**
     * Auto-select mode: the answer is `{ "tool": "...", "params": { ... } }`, wrapped in the search block.
     * Validates that the chosen tool name is among [knownToolNames].
     */
    private fun parseAutoSelectResult(
        llmResult: StructuredLlmResult,
        knownToolNames: List<String>,
    ): LoopConfigGenerationResult {
        val node = llmResult.json ?: return unusable(llmResult)

        val chosenTool = node.get("tool")?.asText()?.takeIf { it.isNotBlank() }
        if (chosenTool == null) {
            logger.warn { "[LoopConfigGeneration] Auto-select response missing 'tool' field. Raw: ${llmResult.rawText}" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                rawResponse = llmResult.rawText,
                warning = "The LLM response did not include a 'tool' field.",
            )
        }
        if (chosenTool !in knownToolNames) {
            logger.warn { "[LoopConfigGeneration] Auto-select chose unknown tool '$chosenTool' (known: $knownToolNames)" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                rawResponse = llmResult.rawText,
                warning = "The LLM chose tool '$chosenTool' which is not available to this agent.",
            )
        }

        val params = node.get("params")?.takeUnless { it.isNull } ?: objectMapper.createObjectNode()
        logger.info { "[LoopConfigGeneration] Auto-select chose tool '$chosenTool'" }
        return LoopConfigGenerationResult(
            loopConfig = wrapInSearchBlock(chosenTool, params),
            warning = FENCE_WARNING.takeIf { llmResult.usedCodeFence },
        )
    }

    /**
     * Wraps [params] in the search block:
     * ```json
     * { "search": { "tool": "SearchTasks", "params": { ... } } }
     * ```
     */
    private fun wrapInSearchBlock(
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
        private const val FENCE_WARNING = "JSON was extracted from a markdown code fence in the LLM response."
        private const val EMPTY_OBJECT_WARNING = "The LLM returned an empty JSON object. The description may be too vague."

        /**
         * System prompt for single-tool mode: the tool is known, only the params are generated.
         */
        private val SINGLE_TOOL_SYSTEM_PROMPT =
            """
            You are a JSON parameter generator for a search tool.

            Given:
            - the tool's JSON Schema (its accepted input)
            - a natural-language description of what the user wants to search for
            - optionally, an "Additional context" block that may provide ids to use in params

            Produce ONLY a valid JSON object that:
            - matches the tool's input schema
            - captures the intent expressed in the description
            - uses the ids given in the additional context when the schema asks for them
            - uses null for optional fields that are not mentioned
            - does NOT invent fields that are not in the schema

            Output the raw JSON object with no explanation, no markdown, no code fences.
            Start your response with '{' and end it with '}'.
            """.trimIndent()

        /**
         * System prompt for auto-select mode: the LLM must also choose the right tool.
         *
         * The response format is `{ "tool": "<toolName>", "params": { ... } }` so that
         * both the tool choice and the params are returned in a single parseable object.
         */
        private val AUTO_SELECT_SYSTEM_PROMPT =
            """
            You are a JSON parameter generator for search tools.

            Given:
            - a list of available tools, each with its name and JSON Schema
            - a natural-language description of what the user wants to search for
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
            - Start your response with '{' and end it with '}'.
            """.trimIndent()
    }
}
