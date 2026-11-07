import { startupLines } from './startup-info'
import { portsFor } from './port-scheme'

const block = (base: number) => {
  const p = portsFor(base)
  return {
    express: p.server,
    agentos: p.agentos,
    factory: p.factory,
    bolt: p.bolt,
    client: p.client,
    cockpit: p.factoryCockpit,
  }
}

describe('startupLines', () => {
  it('packaged: Express serves the client and the cockpit, the rest is internal', () => {
    const b = block(3010)
    expect(startupLines({ dev: false, ...b })).toEqual([
      `Coday     http://localhost:${b.express}/`,
      `Cockpit   http://localhost:${b.express}/factory/`,
      `Internal  AgentOS ${b.agentos} \u00b7 Factory ${b.factory} \u00b7 Neo4j Bolt ${b.bolt}`,
    ])
  })

  it('dev: the Angular dev servers are listed as internal ports', () => {
    const b = block(4000)
    expect(startupLines({ dev: true, ...b })).toEqual([
      `Coday     http://localhost:${b.express}/`,
      `Cockpit   http://localhost:${b.express}/factory/`,
      `Internal  client ${b.client} \u00b7 cockpit ${b.cockpit} \u00b7 AgentOS ${b.agentos} \u00b7 Factory ${b.factory} \u00b7 Bolt ${b.bolt}`,
    ])
  })

  it('an external service shows its own port, and an external AgentOS has no managed Bolt (packaged)', () => {
    const b = block(3000)
    const lines = startupLines({
      dev: false,
      express: b.express,
      externalAgentos: 'http://agentos.internal:8124',
      externalFactory: 'http://factory.internal:8141',
    })
    expect(lines[2]).toBe('Internal  AgentOS 8124 \u00b7 Factory 8141')
  })

  it('leaves the Factory out when it is disabled', () => {
    const b = block(3000)
    expect(startupLines({ dev: false, ...b, factoryDisabled: true })[2]).toBe(
      `Internal  AgentOS ${b.agentos} \u00b7 Neo4j Bolt ${b.bolt}`
    )
  })

  it('prints no Internal line when there is nothing internal to say', () => {
    expect(startupLines({ dev: false, express: 3000, externalAgentos: undefined, factoryDisabled: true })).toHaveLength(
      2
    )
  })
})
