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
        merged[existingIndex] = mergeServerConfigs(merged[existingIndex]!, mcpServer)
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

  // Merge environment variables from all levels (later levels override)
  const mergedEnv = { ...existing.env, ...override.env }

  // Merge whiteListedHostEnvVarNames from all levels (aggregate unique values)
  const mergedEnvVarNames = Array.from(
    new Set([...(existing.whiteListedHostEnvVarNames || []), ...(override.whiteListedHostEnvVarNames || [])])
  )

  // Apply environment variable fallbacks for specific known variables
  const envWithFallbacks = applyEnvFallbacks(mergedEnv, mergedEnvVarNames)

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

    // NoShare: if any level sets noShare to true, it stays true (most restrictive wins)
    noShare: existing.noShare || override.noShare || false,

    // Env: use merged environment with fallbacks applied
    env: envWithFallbacks,

    // EnvVarNames: use merged list of environment variable names
    whiteListedHostEnvVarNames: mergedEnvVarNames.length > 0 ? mergedEnvVarNames : undefined,
  }
}

/**
 * Apply safe defaults to a new MCP server configuration
 * @param server The server configuration to apply defaults to
 * @returns Server configuration with safe defaults applied
 */
function applyServerDefaults(server: McpServerConfig): McpServerConfig {
  const env = server.env || {}
  const envWithFallbacks = applyEnvFallbacks(env, server.whiteListedHostEnvVarNames || [])

  return {
    ...server,
    enabled: server.enabled !== undefined ? server.enabled : true,
    debug: server.debug || false,
    noShare: server.noShare || false,
    args: server.args || [],
    env: envWithFallbacks,
  }
}

/**
 * Default environment variables that are always inherited from the host process.
 * These are considered safe, non-sensitive variables that MCP servers commonly need.
 */
const DEFAULT_INHERITED_ENV_VARS = [
  // Execution environment - essential for running commands
  'PATH', // Command search path (essential)
  'HOME', // User's home directory
  'USER', // Current username
  'TMPDIR', // Temp directory (Unix/Linux/Mac)
  'TEMP', // Temp directory (Windows)
  'TMP', // Alternative temp directory (Windows)

  // Locale and encoding - important for text processing
  'LANG', // Language/locale settings
  'LC_ALL', // Locale override
  'LC_CTYPE', // Character encoding

  // Terminal settings - useful for CLI tools
  'TERM', // Terminal type
  'COLORTERM', // Color support indicator

  // Shell and platform detection
  'SHELL', // User's default shell (Unix/Linux/Mac)
  'OS', // Operating system (Windows)

  // Note: Security-sensitive variables (tokens, keys, passwords, secrets)
  // are NOT included and must be explicitly whitelisted via whiteListedHostEnvVarNames
]

/**
 * Apply environment variable fallbacks from process.env white listed vars + basic non sensitive vars
 * Returns a new object with fallbacks applied
 * @param env The environment variables object to apply fallbacks to
 * @param whiteListedHostEnvVarNames List of environment variable names to look up in process.env
 * @returns A new object with fallbacks applied
 */
function applyEnvFallbacks(env: Record<string, string>, whiteListedHostEnvVarNames: string[]): Record<string, string> {
  const result = { ...env }

  // Combine default whitelist with user-specified whitelist
  const allWhitelistedVars = [...DEFAULT_INHERITED_ENV_VARS, ...whiteListedHostEnvVarNames]

  // Only apply fallbacks for whitelisted variables
  for (const envVarName of allWhitelistedVars) {
    // Only apply fallback if the variable is not already set in env
    if (!result[envVarName] && process.env[envVarName]) {
      result[envVarName] = process.env[envVarName]!
    }
  }

  return result
}
