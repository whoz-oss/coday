package io.biznet.agentos.tools.domain

data class InternalTool(override val name: String, override val version: String,
                        override val description: String,
                        override val inputSchema: String
): StandardTool {
    override fun execute(input: String): String =
        "result of Internal Tool to do ```${description}``` on $input"

}