import { isConfigAgentExposed } from './config-agent-exposure'

describe('isConfigAgentExposed', () => {
  it('flags a config agent that also has an integration able to send data off the server', () => {
    for (const type of ['HTTP_API', 'MCP_HTTP', 'MCP_STDIO', 'BASH', 'TMUX', 'GIT']) {
      expect(isConfigAgentExposed({ configEnabled: true, integrationTypes: [type], hasSubAgents: false })).toBe(true)
    }
  })

  it('flags a config agent with sub-agents', () => {
    expect(isConfigAgentExposed({ configEnabled: true, integrationTypes: [], hasSubAgents: true })).toBe(true)
  })

  it('leaves alone an agent without the config tools, or with only local integrations', () => {
    expect(isConfigAgentExposed({ configEnabled: false, integrationTypes: ['HTTP_API'], hasSubAgents: true })).toBe(
      false
    )
    expect(
      isConfigAgentExposed({ configEnabled: true, integrationTypes: ['DATETIME', 'QUERY_USER'], hasSubAgents: false })
    ).toBe(false)
  })
})
