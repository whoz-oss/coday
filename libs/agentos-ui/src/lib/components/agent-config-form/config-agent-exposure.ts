/**
 * Built-in integration letting an agent read and propose AgentOS configuration changes. Mirrors
 * `ConfigAgentIntegration.TYPE`, pinned by `ConfigAgentIntegrationUnitSpec`.
 */
export const CONFIG_AGENT_TYPE = 'AGENTOS_CONFIG'

/**
 * Integration types that can carry data off the server: network clients, and process or Git access.
 * Mirrored here until integration type descriptors say it themselves (#1520).
 */
const DATA_OUT_INTEGRATION_TYPES = new Set(['HTTP_API', 'MCP_HTTP', 'MCP_STDIO', 'BASH', 'TMUX', 'GIT'])

/**
 * Whether an agent that reads the AgentOS configuration can also send what it read elsewhere,
 * through one of its integrations or a sub-agent. Reads do not go through the user's Apply click,
 * so the form warns about such an agent, without blocking it.
 */
export function isConfigAgentExposed(agent: {
  configEnabled: boolean
  integrationTypes: string[]
  hasSubAgents: boolean
}): boolean {
  return (
    agent.configEnabled &&
    (agent.hasSubAgents || agent.integrationTypes.some((type) => DATA_OUT_INTEGRATION_TYPES.has(type)))
  )
}
