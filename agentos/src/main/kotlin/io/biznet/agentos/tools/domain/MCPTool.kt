package io.biznet.agentos.tools.domain

data class MCPTool(override val name: String, override val version: String,
                   override val description: String,
                   override val paramType: Class<*>? = String::class.java,
                   override val inputSchema: String = NO_ARGS_INPUT
) : StandardTool<String> {
    override fun execute(input: String?): String =
        "result of MCP to do ```${description}``` on $input"

}