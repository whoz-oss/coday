package io.whozoss.agentos.sdk.tool

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<*>?

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
}

const val NO_ARGS_INPUT = """{
                            "${"$"}schema" : "https://json-schema.org/draft/2020-12/schema",
                            "type" : "object",
                            "properties" : { "input": { "type": "null" } },
                            "required" : [ ],
                            "additionalProperties" : false
                        }"""
