package io.whozoss.agentos.sdk.tool

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<*>?

    fun execute(input: T?): String
}

const val NO_ARGS_INPUT = """{
                            "${"$"}schema" : "https://json-schema.org/draft/2020-12/schema",
                            "type" : "object",
                            "properties" : { "input": { "type": "null" } },
                            "required" : [ ],
                            "additionalProperties" : false
                        }"""
