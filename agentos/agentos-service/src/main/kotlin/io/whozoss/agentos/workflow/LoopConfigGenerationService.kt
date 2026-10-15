package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.api.agentConfig.LoopConfigGenerationResult
import mu.KLogging
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

/**
 * Translates a natural-language description into a complete `loopConfig` JSON object
 * ready to set on an [io.whozoss.agentos.agentConfig.AgentConfig].
 *
 * ## How it works
 *
 * Two modes depending on whether [searchToolName] is provided:
 *
 * **Single-tool mode** ([searchToolName] non-null):
 * 1. Look up the named tool in [ResolvedAgentDefinition.tools] and read its [inputSchema].
 * 2. Instruct the LLM to produce a JSON object matching that schema.
 * 3. Parse the response as a [JsonNode] and wrap it in `{ "search": { "tool": "...", "params": ... } }`.
 *
 * **Auto-select mode** ([searchToolName] null):
 * 1. Collect all available tools and their schemas.
 * 2. Instruct the LLM to pick the best tool and produce `{ "tool": "...", "params": { ... } }`.
 * 3. Parse the response, validate that `tool` names a known tool, and wrap in the full
 *    `{ "search": { ... } }` structure.
 *
 * In both modes the result can be pasted directly into [AgentConfigDto.loopConfig].
 *
 * ## Why the LLM is invoked directly (not via ChatClient.stream)
 *
 * This is a short, synchronous, non-streaming call: the result is a small JSON object.
 * [ChatClient.prompt().call().content()] is the idiomatic Spring AI path for that shape.
 *
 * ## Error handling
 *
 * - Unknown [searchToolName]: returns a result with `loopConfig = null` and a descriptive error
 *   in [LoopConfigGenerationResult.rawResponse]. The caller decides how to surface this.
 * - No tools available (auto-select mode): returns `loopConfig = null` immediately.
 * - LLM throws: re-thrown as-is. The controller maps provider errors to HTTP 502.
 * - JSON parse failure after both primary and fallback attempts: returns `loopConfig = null`
 *   with the raw LLM text in [LoopConfigGenerationResult.rawResponse].
 */
@Service
class LoopConfigGenerationService(
    private val chatClientProvider: ChatClientProvider,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Generate `search.params` JSON from [description] using the LLM resolved from
     * [definition].
     *
     * @param definition  Fully-resolved agent definition (model, provider, tools).
     * @param searchToolName  Name of the search tool to look up in [definition.tools].
     * @param description  Natural-language description of the desired search criteria.
     * @return [LoopConfigGenerationResult] with the generated params or an error payload.
     */
    fun generate(
        definition: ResolvedAgentDefinition,
        searchToolName: String?,
        description: String,
    ): LoopConfigGenerationResult =
        when (searchToolName) {
            null -> generateAutoSelect(definition, description)
            else -> generateSingleTool(definition, searchToolName, description)
        }

    // -------------------------------------------------------------------------
    // Single-tool mode
    // -------------------------------------------------------------------------

    private fun generateSingleTool(
        definition: ResolvedAgentDefinition,
        searchToolName: String,
        description: String,
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
                rawResponse =
                    "Tool '$searchToolName' is not available to agent '${definition.name}'. " +
                        "Available tools: $available",
            )
        }

        logger.debug {
            "[LoopConfigGeneration] Generating params for tool '$searchToolName' " +
                "(agent=${definition.name}, schema length=${tool.inputSchema.length})"
        }

        val userMessage =
            UserMessage(
                """
                Tool name: $searchToolName
                Tool input schema:
                ${tool.inputSchema}

                User description:
                $description
                """.trimIndent(),
            )

        val rawText = callLlm(definition, SINGLE_TOOL_SYSTEM_PROMPT, userMessage) ?: return LoopConfigGenerationResult(
            loopConfig = null,
            warning = "The LLM returned no content.",
        )

        logger.debug { "[LoopConfigGeneration] LLM raw response (${rawText.length} chars): $rawText" }
        return parseParamsResult(rawText, searchToolName)
    }

    // -------------------------------------------------------------------------
    // Auto-select mode
    // -------------------------------------------------------------------------

    private fun generateAutoSelect(
        definition: ResolvedAgentDefinition,
        description: String,
    ): LoopConfigGenerationResult {
        val tools = definition.tools.toList()
        if (tools.isEmpty()) {
            logger.warn { "[LoopConfigGeneration] No tools available for agent '${definition.name}' (auto-select mode)" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                rawResponse = "No tools are available to agent '${definition.name}'.",
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

        val userMessage =
            UserMessage(
                """
                Available tools:
                $toolsBlock

                User description:
                $description
                """.trimIndent(),
            )

        val rawText = callLlm(definition, AUTO_SELECT_SYSTEM_PROMPT, userMessage) ?: return LoopConfigGenerationResult(
            loopConfig = null,
            warning = "The LLM returned no content.",
        )

        logger.debug { "[LoopConfigGeneration] LLM raw response (${rawText.length} chars): $rawText" }
        return parseAutoSelectResult(rawText, tools.map { it.name })
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    /** Invokes the LLM and returns the raw text content, or null when the model returns nothing. */
    private fun callLlm(
        definition: ResolvedAgentDefinition,
        systemPrompt: String,
        userMessage: UserMessage,
    ): String? {
        val chatClient =
            chatClientProvider.getChatClient(
                modelConfig = definition.resolvedModel,
                providerConfig = definition.resolvedProvider,
                caseId = null,
                accumulator = null,
            )
        return chatClient
            .prompt(Prompt(listOf(SystemMessage(systemPrompt), userMessage)))
            .call()
            .content()
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the LLM response as a params [JsonNode] (single-tool mode) and wraps it
     * in the full loopConfig structure.
     *
     * Primary attempt: parse the whole string directly.
     * Fallback: extract the content of the first ```json ... ``` or ``` ... ``` fence.
     */
    private fun parseParamsResult(
        rawText: String,
        searchToolName: String,
    ): LoopConfigGenerationResult {
        tryParseJson(rawText)?.let { node ->
            if (node.isObject && node.size() == 0) {
                logger.warn { "[LoopConfigGeneration] LLM returned an empty JSON object for tool '$searchToolName'" }
                return LoopConfigGenerationResult(
                    loopConfig = wrapInLoopConfig(searchToolName, node),
                    warning = "The LLM returned an empty JSON object. The description may be too vague.",
                )
            }
            return LoopConfigGenerationResult(loopConfig = wrapInLoopConfig(searchToolName, node))
        }

        extractFromCodeFence(rawText)?.let { fenced ->
            tryParseJson(fenced)?.let { node ->
                logger.info { "[LoopConfigGeneration] Parsed params via code-fence fallback for tool '$searchToolName'" }
                return LoopConfigGenerationResult(
                    loopConfig = wrapInLoopConfig(searchToolName, node),
                    warning = "JSON was extracted from a markdown code fence in the LLM response.",
                )
            }
        }

        logger.warn { "[LoopConfigGeneration] Could not parse LLM response as JSON for tool '$searchToolName'. Raw: $rawText" }
        return LoopConfigGenerationResult(
            loopConfig = null,
            rawResponse = rawText,
            warning = "The LLM response could not be parsed as a JSON object.",
        )
    }

    /**
     * Parses the LLM response as `{ "tool": "...", "params": { ... } }` (auto-select mode)
     * and wraps it in the full loopConfig structure.
     *
     * Validates that the chosen tool name is among [knownToolNames].
     */
    private fun parseAutoSelectResult(
        rawText: String,
        knownToolNames: List<String>,
    ): LoopConfigGenerationResult {
        val node =
            tryParseJson(rawText)
                ?: extractFromCodeFence(rawText)?.let { tryParseJson(it) }
                ?: run {
                    logger.warn { "[LoopConfigGeneration] Could not parse auto-select LLM response as JSON. Raw: $rawText" }
                    return LoopConfigGenerationResult(
                        loopConfig = null,
                        rawResponse = rawText,
                        warning = "The LLM response could not be parsed as a JSON object.",
                    )
                }

        val chosenTool = node.get("tool")?.asText()?.takeIf { it.isNotBlank() }
        if (chosenTool == null) {
            logger.warn { "[LoopConfigGeneration] Auto-select response missing 'tool' field. Raw: $rawText" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                rawResponse = rawText,
                warning = "The LLM response did not include a 'tool' field.",
            )
        }
        if (chosenTool !in knownToolNames) {
            logger.warn { "[LoopConfigGeneration] Auto-select chose unknown tool '$chosenTool' (known: $knownToolNames)" }
            return LoopConfigGenerationResult(
                loopConfig = null,
                rawResponse = rawText,
                warning = "The LLM chose tool '$chosenTool' which is not available to this agent.",
            )
        }

        val params = node.get("params") ?: objectMapper.createObjectNode()
        val warning =
            if (node.path("params").isMissingNode) {
                "JSON was extracted from a markdown code fence in the LLM response.".takeIf {
                    rawText.contains("```")
                }
            } else {
                null
            }

        logger.info { "[LoopConfigGeneration] Auto-select chose tool '$chosenTool'" }
        return LoopConfigGenerationResult(
            loopConfig = wrapInLoopConfig(chosenTool, params),
            warning = warning,
        )
    }

    /**
     * Wraps [params] in the full loopConfig structure:
     * ```json
     * { "search": { "tool": "SearchTasks", "params": { ... } } }
     * ```
     */
    private fun wrapInLoopConfig(
        searchToolName: String,
        params: JsonNode,
    ): JsonNode =
        objectMapper.createObjectNode().apply {
            putObject("search").apply {
                put("tool", searchToolName)
                set<JsonNode>("params", params)
            }
        }

    /** Parses [text] as a [JsonNode], returning null on any parse error. */
    private fun tryParseJson(text: String): JsonNode? =
        runCatching { objectMapper.readTree(text.trim()).takeIf { it != null } }.getOrNull()

    /**
     * Extracts the content inside the first triple-backtick code fence, if present.
     * Handles both ` ```json ` and bare ` ``` ` fences.
     *
     * Returns null when no fence is found.
     */
    private fun extractFromCodeFence(text: String): String? {
        val fenceStart = CODE_FENCE_REGEX.find(text) ?: return null
        val contentStart = fenceStart.range.last + 1
        val fenceEnd = text.indexOf("```", contentStart)
        if (fenceEnd == -1) return null
        return text.substring(contentStart, fenceEnd).trim()
    }

    companion object : KLogging() {
        private val CODE_FENCE_REGEX = Regex("```(?:json)?\\s*\\n?")

        /**
         * System prompt for single-tool mode: the tool is known, only the params are generated.
         */
        private val SINGLE_TOOL_SYSTEM_PROMPT =
            """
            You are a JSON parameter generator for a search tool.
            
            Given:
            - the tool's JSON Schema (its accepted input)
            - a natural-language description of what the user wants to search for
            
            Produce ONLY a valid JSON object that:
            - matches the tool's input schema
            - captures the intent expressed in the description
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
            
            Select the most appropriate tool and produce ONLY a JSON object in this exact format:
            {
              "tool": "<chosen tool name>",
              "params": { <parameters matching the chosen tool's schema> }
            }
            
            Rules:
            - Choose the tool whose schema best fits the user's description.
            - params must match the chosen tool's input schema exactly.
            - Use null for optional fields that are not mentioned.
            - Do NOT invent fields that are not in the schema.
            - Do NOT add explanation, markdown, or code fences.
            - Start your response with '{' and end it with '}'.
            """.trimIndent()
    }
}
