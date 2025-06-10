import { McpServerConfig } from '../model/mcp-server-config'

/**
 * Merge MCP servers from multiple configuration levels
 * @param codayServers Servers from coday.yaml (lowest priority)
 * @param projectServers Servers from project config (medium priority)
 * @param userServers Servers from user config (highest priority)
 * @returns Merged array of MCP server configurations
 */
export function mergeMcpConfigs(
  codayServers: McpServerConfig[],
  projectServers: McpServerConfig[],
  userServers: McpServerConfig[]
): McpServerConfig[] {
  const merged: McpServerConfig[] = []

  // Process in order of precedence: coday -> project -> user
  const levels = [
    { name: 'CODAY', servers: codayServers },
    { name: 'PROJECT', servers: projectServers },
    { name: 'USER', servers: userServers },
  ]

  for (const level of levels) {
    for (const mcpServer of level.servers) {
      const existingIndex = merged.findIndex((m) => m.id === mcpServer.id)

      if (existingIndex >= 0) {
        // Merge with existing MCP server using specific rules
        merged[existingIndex] = mergeServerConfigs(merged[existingIndex], mcpServer)
      } else {
        // Add new MCP server with safe defaults
        merged.push(applyServerDefaults(mcpServer))
      }
    }
  }

  return merged
}

/**
 * Merge two MCP server configurations with specific aggregation rules
 * @param existing The existing server configuration (lower priority)
 * @param override The overriding server configuration (higher priority)
 * @returns Merged server configuration
 */
function mergeServerConfigs(existing: McpServerConfig, override: McpServerConfig): McpServerConfig {
  // Start with existing config, then apply overrides
  const baseConfig = {
    ...existing,
    ...override,
  }

  // Apply special merging rules for specific properties:
  return {
    ...baseConfig,

    // Args: aggregate all args from all levels in order
    args: [...(existing.args || []), ...(override.args || [])],

    // AllowedTools: aggregate all allowed tools from all levels
    allowedTools:
      existing.allowedTools || override.allowedTools
        ? [...(existing.allowedTools || []), ...(override.allowedTools || [])]
        : undefined,

    // Debug: if any level sets debug to true, it stays true
    debug: existing.debug || override.debug || false,

    // Enabled: use the latest level's definition (last one wins), default to true if undefined
    enabled:
      override.enabled !== undefined ? override.enabled : existing.enabled !== undefined ? existing.enabled : true,

    // Env: merge environment variables from all levels (later levels override)
    env: { ...existing.env, ...override.env },
  }
}

/**
 * Apply safe defaults to a new MCP server configuration
 * @param server The server configuration to apply defaults to
 * @returns Server configuration with safe defaults applied
 */
function applyServerDefaults(server: McpServerConfig): McpServerConfig {
  return {
    ...server,
    enabled: server.enabled !== undefined ? server.enabled : true,
    debug: server.debug || false,
    args: server.args || [],
    env: server.env || {},
  }
}
