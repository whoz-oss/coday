import { createHash } from 'crypto'
import { McpServerConfig } from '../../model/mcp-server-config'

/**
 * Compute a deterministic hash for an MCP server configuration.
 * This hash is used to identify identical MCP instances that can be shared.
 *
 * The hash is based on the resolved configuration that affects the MCP server behavior:
 * - command or url (transport type and target)
 * - args (command arguments)
 * - env (environment variables)
 * - cwd (working directory)
 * - debug (affects inspector process)
 *
 * The hash is order-independent for environment variables only:
 * - args preserve their order (positional arguments matter)
 * - env keys are sorted alphabetically (order doesn't matter for env vars)
 *
 * Metadata fields are excluded from the hash:
 * - id, name (just labels)
 * - enabled (runtime toggle, doesn't affect behavior)
 * - allowedTools (filtering, not server behavior)
 * - authToken (considered part of env if needed)
 * - noShare (affects pooling logic, not server behavior)
 *
 * @param config The MCP server configuration
 * @returns A SHA-256 hash string (64 hex characters)
 */
export function computeMcpConfigHash(config: McpServerConfig): string {
  // If noShare is true, return a unique hash to prevent sharing
  if (config.noShare) {
    return `no-share-${Date.now()}-${Math.random().toString(36).substring(2)}`
  }

  // Build a normalized object for hashing
  const hashData: Record<string, any> = {}

  // Transport configuration
  if (config.command) {
    hashData.command = config.command
  }
  if (config.url) {
    hashData.url = config.url
  }

  // Arguments (preserve order - positional arguments matter)
  if (config.args && config.args.length > 0) {
    hashData.args = config.args
  }

  // Environment variables (sorted by key for order-independence)
  if (config.env && Object.keys(config.env).length > 0) {
    const sortedEnv: Record<string, string> = {}
    Object.keys(config.env)
      .sort()
      .forEach((key) => {
        const value = config.env![key]
        if (value !== undefined) {
          sortedEnv[key] = value
        }
      })
    hashData.env = sortedEnv
  }

  // Working directory
  if (config.cwd) {
    hashData.cwd = config.cwd
  }

  // Debug mode (affects inspector process)
  if (config.debug) {
    hashData.debug = config.debug
  }

  // Compute SHA-256 hash
  const jsonString = JSON.stringify(hashData)
  return createHash('sha256').update(jsonString).digest('hex')
}
