package io.biznet.agentos.tools.service

import io.biznet.agentos.tools.domain.MCPTool
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Provider for MCP (Model Context Protocol) tools.
 * 
 * This service creates and manages MCP sync clients from configuration descriptions.
 * Each MCP client can expose multiple tools that can be used by AI agents.
 * 
 * Configuration format examples:
 * - "stdio://path/to/mcp-server" - Local MCP server via stdio
 * - "sse://http://localhost:8080/mcp" - Remote MCP server via SSE
 */
@Service
class MCPToolsProvider {
    
    private val logger = LoggerFactory.getLogger(MCPToolsProvider::class.java)
    
    /**
     * Creates MCP sync clients from configuration descriptions.
     * 
     * @param mcpClientSpecs List of MCP client configuration strings
     * @return List of initialized McpSyncClient instances
     */
    fun mcpSyncClients(mcpClientSpecs: List<String>): List<McpSyncClient> {
        return mcpClientSpecs.mapNotNull { specContent ->
            try {
                createMcpSyncClientFromSpecContent(specContent)
            } catch (e: Exception) {
                logger.error("Failed to create MCP client from spec: $specContent", e)
                null
            }
        }
    }

    private fun createMcpSyncClientFromSpecContent(specContent: String): McpSyncClient? {
        val mcpClientSpec: McpClient.SyncSpec? = null // TODO :  read mcp client spec from mcpClientSpecContent
        return mcpClientSpec?.build()
    }

    /**
     * Creates MCP tools from sync clients.
     * 
     * @param mcpClients List of MCP sync clients
     * @return List of MCPTool instances wrapping the available tools
     */
    fun createMcpTools(mcpClients: List<McpSyncClient>): List<MCPTool> {
        val tools = mutableListOf<MCPTool>()
        
        mcpClients.forEach { client ->
            try {
                // List available tools from the MCP server
                val listToolsResponse = client.listTools()
                
                listToolsResponse.tools.forEach { tool ->
                    val mcpTool = MCPTool(
                        name = tool.name,
                        version = "1.0", // MCP doesn't provide version, use default
                        description = tool.description ?: "MCP tool: ${tool.name}",
                        inputSchema = tool.inputSchema?.toString() ?: """{"type": "object", "properties": {}}"""
                    )
                    tools.add(mcpTool)
                }
                
                logger.info("Created ${listToolsResponse.tools.size} tools from MCP client")
            } catch (e: Exception) {
                logger.error("Failed to list tools from MCP client", e)
            }
        }
        
        return tools
    }
}
