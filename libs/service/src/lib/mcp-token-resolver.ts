import { McpServerConfig } from '@coday/model'

/**
 * Tokens available for substitution in MCP server config fields.
 *
 * Usage in coday.yaml:
 *   args:
 *     - --workspacePath={{projectRoot}}
 *
 * Available tokens:
 *   {{projectRoot}}  — absolute path to the project root (where coday.yaml lives)
 */
export type TokenContext = {
  projectRoot: string
}

/**
 * Replace all known {{token}} placeholders in a string value.
 */
export function resolveTokensInString(value: string, ctx: TokenContext): string {
  return value.replace(/\{\{projectRoot\}\}/g, ctx.projectRoot)
}

/**
 * Resolve tokens in all string fields of an MCP server config that
 * may contain user-defined paths or values: command, args, cwd, env values.
 *
 * Returns a new McpServerConfig object — the original is not mutated.
 */
export function resolveServerTokens(server: McpServerConfig, projectRoot: string): McpServerConfig {
  const ctx: TokenContext = { projectRoot }

  return {
    ...server,
    command: server.command ? resolveTokensInString(server.command, ctx) : server.command,
    args: server.args?.map((arg) => resolveTokensInString(arg, ctx)),
    cwd: server.cwd ? resolveTokensInString(server.cwd, ctx) : server.cwd,
    env: server.env
      ? Object.fromEntries(Object.entries(server.env).map(([k, v]) => [k, resolveTokensInString(v, ctx)]))
      : server.env,
  }
}
