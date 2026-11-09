import { chooseStackPorts, parsePreferredPorts, stackRequestFromEnv, StackRequest } from './ports'
import { candidateBases, PORT_BASES, PORT_BLOCK_SIZE, portsFor } from './port-scheme'

const prod = candidateBases('prod')
const local = candidateBases('local')
const dev = candidateBases('dev')

/** Every port of a block, role or not: a stranger holding any port of the block it uses. */
const role = (base: number, name: keyof ReturnType<typeof portsFor>): number => portsFor(base)[name]
const blockOf = (base: number): number[] => Array.from({ length: PORT_BLOCK_SIZE }, (_, i) => base + i)

const freeExcept =
  (busy: number[]) =>
  async (port: number): Promise<boolean> =>
    !busy.includes(port)

const noOs = async (): Promise<number> => {
  throw new Error('no OS port expected')
}

const request = (extra: Partial<StackRequest> & Pick<StackRequest, 'profile'>): StackRequest => ({
  isFree: async () => true,
  ephemeral: noOs,
  findOwner: async () => undefined,
  platform: 'linux',
  ...extra,
})

describe('chooseStackPorts', () => {
  describe('a free block', () => {
    it.each([
      ['prod', prod[0] as number],
      ['local', local[0] as number],
      ['dev', dev[0] as number],
    ] as const)('uses the first block of the %s profile, one offset per role', async (profile, base) => {
      const { base: chosen, ports } = await chooseStackPorts(request({ profile }))
      const block = portsFor(base)
      expect(chosen).toBe(base)
      expect(ports).toEqual({
        express: block.server,
        agentos: block.agentos,
        agentosBolt: block.bolt,
        factory: block.factory,
      })
    })

    it('is not disturbed by the other profiles running (a dev and a local stack up: prod stays on its first block)', async () => {
      const busy = [...blockOf(PORT_BASES.dev), ...blockOf(PORT_BASES.local)]
      const { base } = await chooseStackPorts(request({ profile: 'prod', isFree: freeExcept(busy) }))
      expect(base).toBe(PORT_BASES.prod)
    })

    it('does not log anything when it stays on the first block', async () => {
      const log: string[] = []
      await chooseStackPorts(request({ profile: 'prod', log: (m) => log.push(m) }))
      expect(log).toEqual([])
    })
  })

  describe('block fallback (packaged stacks)', () => {
    it.each([
      ['Express', 'server'],
      ['AgentOS', 'agentos'],
      ['Factory', 'factory'],
      ['Bolt', 'bolt'],
    ] as const)('moves the WHOLE prod stack to the next block when %s is taken', async (_label, taken) => {
      const { base, ports } = await chooseStackPorts(
        request({ profile: 'prod', isFree: freeExcept([role(prod[0] as number, taken)]) })
      )
      const block = portsFor(prod[1] as number)
      expect(base).toBe(prod[1])
      expect(ports).toEqual({
        express: block.server,
        agentos: block.agentos,
        agentosBolt: block.bolt,
        factory: block.factory,
      })
    })

    it('ignores a taken RESERVED port: the client and cockpit ports are not used by a packaged stack', async () => {
      const reserved = [role(prod[0] as number, 'client'), role(prod[0] as number, 'factoryCockpit')]
      const { base } = await chooseStackPorts(request({ profile: 'prod', isFree: freeExcept(reserved) }))
      expect(base).toBe(prod[0])
    })

    it('ignores a taken spare offset (3, 4, 8, 9): only the ports the stack uses count', async () => {
      const spare = [3, 4, 8, 9].map((offset) => (prod[0] as number) + offset)
      const { base } = await chooseStackPorts(request({ profile: 'prod', isFree: freeExcept(spare) }))
      expect(base).toBe(prod[0])
    })

    it('walks on while blocks are taken, and logs once: port, owner when known, new block and URL', async () => {
      const log: string[] = []
      const busy = [role(prod[0] as number, 'server'), role(prod[1] as number, 'agentos')]
      const { base, ports } = await chooseStackPorts(
        request({
          profile: 'prod',
          isFree: freeExcept(busy),
          findOwner: async (port) => (port === busy[0] ? 'node, pid 4242' : undefined),
          log: (m) => log.push(m),
        })
      )
      expect(base).toBe(prod[2])
      expect(log).toEqual([
        `Port ${busy[0]} is in use (by node, pid 4242), Coday starts on block ${prod[2]}: http://localhost:${ports.express}/`,
      ])
    })

    it('names no process when it cannot be found', async () => {
      const log: string[] = []
      await chooseStackPorts(
        request({
          profile: 'prod',
          isFree: freeExcept([role(prod[0] as number, 'server')]),
          findOwner: async () => {
            throw new Error('no lsof')
          },
          log: (m) => log.push(m),
        })
      )
      expect(log).toHaveLength(1)
      expect(log[0]).toMatch(/^Port \d+ is in use, Coday starts on block \d+: http:\/\/localhost:\d+\/$/)
    })

    it('local moves along its own blocks', async () => {
      const { base } = await chooseStackPorts(
        request({ profile: 'local', isFree: freeExcept([role(local[0] as number, 'agentos')]) })
      )
      expect(base).toBe(local[1])
    })

    it('prod and local, both falling back, never collide (the blocks interleave)', async () => {
      // A prod stack that took its first block, a local one whose first block holds a stranger: each moves on.
      const prodStack = await chooseStackPorts(request({ profile: 'prod' }))
      const taken = new Set(blockOf(prodStack.base as number))
      const localStack = await chooseStackPorts(
        request({ profile: 'local', isFree: freeExcept([role(local[0] as number, 'server')]) })
      )
      const used = Object.values(localStack.ports)
      for (const port of used) expect(taken.has(port)).toBe(false)
      // And the other way round: prod moving on does not land in a local block.
      const prodMoved = await chooseStackPorts(
        request({ profile: 'prod', isFree: freeExcept([role(prod[0] as number, 'server')]) })
      )
      for (const port of Object.values(prodMoved.ports)) expect(blockOf(local[0] as number)).not.toContain(port)
    })

    it('every prod block taken and the OS assigns the ports, said once', async () => {
      const log: string[] = []
      let next = 50000
      const { base, ports } = await chooseStackPorts(
        request({
          profile: 'prod',
          isFree: freeExcept(prod.map((b) => role(b, 'agentos'))),
          ephemeral: async () => next++,
          log: (m) => log.push(m),
        })
      )
      expect(base).toBeUndefined()
      expect(new Set(Object.values(ports)).size).toBe(4)
      for (const port of Object.values(ports)) expect(port).toBeGreaterThanOrEqual(50000)
      expect(log).toHaveLength(1)
      expect(log[0]).toContain('is taken')
      expect(log[0]).toContain(`http://localhost:${ports.express}/`)
    })

    it('needs no port for an external AgentOS / Factory: only what the stack runs is checked', async () => {
      const block = portsFor(prod[0] as number)
      const { base } = await chooseStackPorts(
        request({
          profile: 'prod',
          managed: { agentos: false, factory: false },
          isFree: freeExcept([block.agentos, block.bolt, block.factory]),
        })
      )
      expect(base).toBe(prod[0])
    })
  })

  describe('remembered block', () => {
    it('is tried first when free', async () => {
      const { base } = await chooseStackPorts(request({ profile: 'prod', remembered: prod[3] }))
      expect(base).toBe(prod[3])
    })

    it('says it started on the block of its last start', async () => {
      const log: string[] = []
      await chooseStackPorts(request({ profile: 'prod', remembered: prod[1], log: (m) => log.push(m) }))
      expect(log).toHaveLength(1)
      expect(log[0]).toContain(`block ${prod[1]}`)
    })

    it('is dropped when taken: back to the normal list, first block first', async () => {
      const { base } = await chooseStackPorts(
        request({ profile: 'prod', remembered: prod[3], isFree: freeExcept([role(prod[3] as number, 'server')]) })
      )
      expect(base).toBe(prod[0])
    })

    it('is ignored when it is not a block of the profile (a local block remembered by a prod run)', async () => {
      const { base } = await chooseStackPorts(request({ profile: 'prod', remembered: local[0] }))
      expect(base).toBe(prod[0])
    })

    it('does not repeat the first block when it is the remembered one', async () => {
      const asked: number[] = []
      await chooseStackPorts(
        request({
          profile: 'prod',
          remembered: prod[0],
          isFree: async (port) => (asked.push(port), true),
        })
      )
      expect(asked).toHaveLength(4)
    })
  })

  describe('strictness: only an explicit choice', () => {
    it('CODAY_PORT_BASE: strict on that block, no fallback', async () => {
      const base = prod[5] as number
      await expect(
        chooseStackPorts(request({ profile: 'prod', explicitBase: base, isFree: freeExcept([role(base, 'agentos')]) }))
      ).rejects.toThrow(`Port ${role(base, 'agentos')} is already in use`)
    })

    it('CODAY_PORT_BASE: the block is used when free, whatever the profile', async () => {
      const base = prod[5] as number
      const { base: chosen } = await chooseStackPorts(request({ profile: 'dev', explicitBase: base }))
      expect(chosen).toBe(base)
    })

    it('a remembered block does not override an explicit base', async () => {
      const { base } = await chooseStackPorts(request({ profile: 'prod', explicitBase: prod[1], remembered: prod[3] }))
      expect(base).toBe(prod[1])
    })

    it('--port / PORT: Express is strict on it, the services still take a block', async () => {
      const { base, ports } = await chooseStackPorts(request({ profile: 'prod', explicitExpress: 8123 }))
      expect(ports.express).toBe(8123)
      expect(ports.agentos).toBe(role(prod[0] as number, 'agentos'))
      expect(base).toBe(prod[0])
    })

    it('--port / PORT taken: fails with the message of the taken port, never falls back', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'prod', explicitExpress: 8123, isFree: freeExcept([8123]) }))
      ).rejects.toThrow('Port 8123 is already in use')
    })

    it('an explicit port used by the stack is not asked twice (Express on a port of the block)', async () => {
      // Express forced on the AgentOS port of the first block: the services move to the next block.
      const forced = role(prod[0] as number, 'agentos')
      const { base, ports } = await chooseStackPorts(request({ profile: 'prod', explicitExpress: forced }))
      expect(ports.express).toBe(forced)
      expect(base).toBe(prod[1])
    })

    it('CODAY_*_PORT of a managed service taken: fails, never falls back', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'prod', preferred: { agentos: 8324 }, isFree: freeExcept([8324]) }))
      ).rejects.toThrow('Port 8324 is already in use')
    })

    it('CODAY_*_PORT replace the port of the block for their service only', async () => {
      const { ports } = await chooseStackPorts(
        request({ profile: 'prod', preferred: { agentos: 8324, agentosBolt: 7888, factory: 8341 } })
      )
      expect(ports).toEqual({
        express: role(prod[0] as number, 'server'),
        agentos: 8324,
        agentosBolt: 7888,
        factory: 8341,
      })
    })

    it('a CODAY_*_PORT of a service that is external is not checked', async () => {
      const { base } = await chooseStackPorts(
        request({
          profile: 'prod',
          preferred: { agentos: 8324 },
          managed: { agentos: false, factory: true },
          isFree: freeExcept([8324]),
        })
      )
      expect(base).toBe(prod[0])
    })

    it('the dev profile is strict (static Nx / Angular configs cannot follow a move)', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'dev', isFree: freeExcept([role(dev[0] as number, 'agentos')]) }))
      ).rejects.toThrow(`Port ${role(dev[0] as number, 'agentos')} is already in use`)
    })

    it('the taken-port error names the process holding it and the AirPlay hint on macOS', async () => {
      await expect(
        chooseStackPorts(
          request({
            profile: 'prod',
            explicitExpress: 5000,
            platform: 'darwin',
            isFree: freeExcept([5000]),
            findOwner: async () => 'ControlCenter, pid 612',
          })
        )
      ).rejects.toThrow(/Port 5000 is already in use \(by ControlCenter, pid 612\)\. .*AirPlay Receiver/)
    })
  })
})

describe('stackRequestFromEnv', () => {
  it('derives profile and strictness from the environment', () => {
    expect(stackRequestFromEnv({})).toMatchObject({
      profile: 'prod',
      explicitBase: undefined,
      explicitExpress: undefined,
    })
    expect(stackRequestFromEnv({ BUILD_ENV: 'development' })).toMatchObject({ profile: 'dev' })
    expect(stackRequestFromEnv({ CODAY_PORT_PROFILE: 'local' })).toMatchObject({ profile: 'local' })
    expect(stackRequestFromEnv({ CODAY_PORT_BASE: '3050' })).toMatchObject({ explicitBase: 3050 })
    expect(stackRequestFromEnv({ PORT: '8123' })).toMatchObject({ explicitExpress: 8123 })
  })

  it('--port wins over PORT; an invalid value is ignored', () => {
    expect(stackRequestFromEnv({ PORT: '8123' }, { port: 8222 })).toMatchObject({ explicitExpress: 8222 })
    expect(stackRequestFromEnv({ PORT: 'abc' })).toMatchObject({ explicitExpress: undefined })
    expect(stackRequestFromEnv({ PORT: '' })).toMatchObject({ explicitExpress: undefined })
  })

  it('web:local style (profile local, no explicit port, no explicit base) is NOT strict', () => {
    const req = stackRequestFromEnv({ CODAY_PORT_PROFILE: 'local' })
    expect(req.explicitBase).toBeUndefined()
    expect(req.explicitExpress).toBeUndefined()
  })
})

describe('parsePreferredPorts', () => {
  it('parses the env vars and ignores invalid values', () => {
    expect(parsePreferredPorts({ CODAY_AGENTOS_PORT: '8324', CODAY_FACTORY_PORT: '8341' })).toEqual({
      agentos: 8324,
      factory: 8341,
    })
    expect(parsePreferredPorts({ CODAY_AGENTOS_BOLT_PORT: '7888' })).toEqual({ agentosBolt: 7888 })
    expect(
      parsePreferredPorts({ CODAY_AGENTOS_PORT: 'abc', CODAY_FACTORY_PORT: '0', CODAY_AGENTOS_BOLT_PORT: '70000' })
    ).toEqual({})
    expect(parsePreferredPorts({})).toEqual({})
  })
})
