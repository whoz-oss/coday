package io.biznet.agentos.tools.domain

data class MCPTool(override val name: String, override val version: String,
                   override val description: String,
                   override val inputSchema: String
) : StandardTool {
    override fun execute(input: String): String =
        "result of MCP to do ```${description}``` on $input"

}