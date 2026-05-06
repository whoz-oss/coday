package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<T>?

    fun execute(
        input: T?,
        context: ToolContext,
    ): String

    /**
     * Deserialize raw JSON produced by the LLM and execute the tool.
     *
     * Called from the service layer (app classloader) with the raw JSON string that
     * Spring AI extracted from the LLM response. Deserialization is performed here,
     * inside the plugin classloader, so [paramType] is always resolvable without
     * crossing classloader boundaries.
     *
     * @param json Raw JSON string from the LLM (e.g. `{"timezone":"UTC"}`)
     * @param context The execution context for this tool call
     * @return The execution result as a String
     */
    fun executeWithJson(
        json: String?,
        context: ToolContext,
    ): String {
        val type = paramType
        val input: T? =
            if (type == null || json.isNullOrBlank()) {
                // No paramType or truly empty args — fall back to tool defaults via execute(null)
                null
            } else {
                // Parse JSON (including "{}" so that Kotlin data-class defaults kick in)
                objectMapper.readValue(json, type)
            }
        return execute(input, context)
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
