package io.biznet.agentos.tools.service

import io.biznet.agentos.tools.domain.StandardTool
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.stereotype.Service

@Service
class ToolRegistry(
    private val mcpClients: List<McpSyncClient>,
    private val mcpToolsProvider: MCPToolsProvider
) {

    fun findTools(): List<StandardTool<*>> {
        // Get MCP tools from configured clients
        val mcpTools = mcpToolsProvider.createMcpTools(mcpClients)
        
        // Combine with static tools
        return mcpTools + listOf(
        object : StandardTool<OpInput> {
            override val name: String = "mean_tool"
            override val description: String = "process the mean of a list of numbers"
            override val paramType: Class<*>? = OpInput::class.java
            override val inputSchema: String
                get() = """{
  "${"$"}schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {"input": {
        "type": "object",
        "properties": {
            "numbers": {
              "type": "array",
              "items": {
                "type": "number"
              }
            }
        }
    }
  },
  "required" : [ "numbers" ],
  "additionalProperties" : false
}"""
            override val version: String
                get() = "V1"

            override fun execute(input: OpInput?): String {
                val numbers = input?.numbers
                return if (numbers.isNullOrEmpty()) {
                    "cannot process mean without numbers"
                } else  {
                    "${numbers.sum() / numbers.size}"
                }
            }

        }
        )
    }
}
data class OpInput(val numbers: List<Double>)