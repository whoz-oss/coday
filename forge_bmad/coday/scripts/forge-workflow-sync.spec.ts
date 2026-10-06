import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('forge-workflow-sync script', () => {
  const source = readFileSync(join(__dirname, 'forge-workflow-sync.ts'), 'utf8')

  it('calls only the explicit Forge workflow synchronization route', () => {
    expect(source).toContain('/api/factory/forge/projections/${encodeURIComponent(ticketId)}/sync')
    expect(source).not.toContain('/api/factory/forge/gantt')
  })

  it('reads changed from the route data envelope for published and unchanged output', () => {
    expect(source).toContain('response.data?.changed === true')
    expect(source).toContain("? 'published' : 'unchanged'")
  })

  it('keeps HTTP and network publication failures non-blocking', () => {
    expect(source).toContain("body: '{}'")
    expect(source).toContain('return // best effort')
    expect(source).toContain('Factory server unreachable')
  })
})
