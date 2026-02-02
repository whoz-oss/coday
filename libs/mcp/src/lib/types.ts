/**
 * Factory for MCP server tools that dynamically discovers and exposes tools
 * from configured MCP servers.
 *
 * This factory creates server-specific integration factories for each MCP server
 * with the naming convention MCP_<serverId> to allow agents to select specific
 * MCP servers in their integration configuration.
 */
// Define interfaces based on MCP SDK types
export interface ToolInfo {
  name: string
  description?: string
  inputSchema: {
    type: 'object'
    properties?: Record<string, any>
  }
}

export interface ResourceTemplate {
  name: string
  description?: string
  uriTemplate: string
  contentType?: string
}
