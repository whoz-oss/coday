package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<T>?

    fun execute(input: T?): String

    /**
     * Type-erased execution path for tools.
     * This method handles the internal cast from Any? to T?, allowing callers
     * to invoke tool execution without reflection or unsafe casts.
     *
     * @param input The input parameter as Any? (will be cast to T? internally)
     * @return The execution result as a String
     * @throws ClassCastException if the input cannot be cast to the expected type T
     */
    @Suppress("UNCHECKED_CAST")
    fun executeWithAny(input: Any?): String = execute(input as? T)

    /**
     * Deserialize raw JSON produced by the LLM and execute the tool.
     *
     * Called from the service layer (app classloader) with the raw JSON string that
     * Spring AI extracted from the LLM response. Deserialization is performed here,
     * inside the plugin classloader, so [paramType] is always resolvable without
     * crossing classloader boundaries.
     *
     * @param json Raw JSON string from the LLM (e.g. `{"timezone":"UTC"}`)
     * @return The execution result as a String
     */

    fun executeWithJson(json: String?): String {
        val type = paramType
        val input: T? =
            if (type == null || json.isNullOrBlank() || json == "{}") {
                null
            } else {
                objectMapper.readValue(json, type)
            }
        return execute(input)
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}

const val NO_ARGS_INPUT = """{
                            "${"$"}schema" : "https://json-schema.org/draft/2020-12/schema",
                            "type" : "object",
                            "properties" : { "input": { "type": "null" } },
                            "required" : [ ],
                            "additionalProperties" : false
                        }"""
