import { createHash } from 'crypto'
import { McpServerConfig } from '@coday/model'

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
 * For OAuth servers (oauth2: true) and noShare servers, the instance must not be
 * shared across threads. Pass the threadId to scope the key per thread, ensuring
 * the same factory is reused within a thread (so stored tokens are not lost) while
 * remaining isolated from other threads.
 *
 * @param config The MCP server configuration
 * @param threadId Optional thread ID — required for oauth2/noShare servers to scope the key per thread
 * @returns A deterministic key string
 */
export function computeMcpConfigHash(config: McpServerConfig, threadId?: string): string {
  // oauth2 and noShare both prevent sharing across users/threads.
  // Use a per-thread deterministic key so the same factory is reused within a thread
  // (preserving in-memory OAuth state and stored tokens) but never shared across threads.
  if (config.noShare || config.oauth2) {
    const scope = threadId ?? `no-thread-${Date.now()}-${Math.random().toString(36).substring(2)}`
    return `no-share-${config.id}-${scope}`
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
