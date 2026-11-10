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

const request = (extra: Partial<StackRequest> & Pick<StackRequest, 'profile'>): StackRequest => ({
  isFree: async () => true,
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
  })

  describe('a taken port fails the start, whatever the profile (stable ports for OAuth redirect URIs)', () => {
    it.each([
      ['prod', 'Express', 'server'],
      ['prod', 'AgentOS', 'agentos'],
      ['prod', 'Factory', 'factory'],
      ['prod', 'Bolt', 'bolt'],
      ['local', 'Express', 'server'],
      ['local', 'AgentOS', 'agentos'],
      ['local', 'Factory', 'factory'],
      ['local', 'Bolt', 'bolt'],
    ] as const)('%s: fails when %s is taken, no other block is tried', async (profile, _label, taken) => {
      const base = PORT_BASES[profile]
      const port = role(base, taken)
      const asked: number[] = []
      await expect(
        chooseStackPorts(
          request({
            profile,
            isFree: async (p) => {
              asked.push(p)
              return p !== port
            },
          })
        )
      ).rejects.toThrow(`Port ${port} is already in use`)
      // Only the block of the profile was probed.
      for (const p of asked) expect(blockOf(base)).toContain(p)
    })

    it('the message names the process holding the port and what to do', async () => {
      const port = role(prod[0] as number, 'agentos')
      const error = await chooseStackPorts(
        request({
          profile: 'prod',
          isFree: freeExcept([port]),
          findOwner: async (p) => (p === port ? 'node, pid 4242' : undefined),
        })
      ).catch((e: Error) => e)
      expect((error as Error).message).toBe(
        `Port ${port} is already in use (by node, pid 4242). Stop what uses it or choose another port (PORT=<n>, or CODAY_PORT_BASE=<n> for a whole block).`
      )
      expect((error as Error).message).not.toContain('\n')
    })

    it('names no process when it cannot be found', async () => {
      await expect(
        chooseStackPorts(
          request({
            profile: 'prod',
            isFree: freeExcept([role(prod[0] as number, 'server')]),
            findOwner: async () => {
              throw new Error('no lsof')
            },
          })
        )
      ).rejects.toThrow(/^Port \d+ is already in use\. Stop what uses it/)
    })

    it('the dev profile is strict as well', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'dev', isFree: freeExcept([role(dev[0] as number, 'agentos')]) }))
      ).rejects.toThrow(`Port ${role(dev[0] as number, 'agentos')} is already in use`)
    })

    it('even when every other block is free, and when every port of the block is taken', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'prod', isFree: freeExcept(blockOf(prod[0] as number)) }))
      ).rejects.toThrow('is already in use')
    })

    it('the AirPlay hint shows on macOS for 5000', async () => {
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

  describe('services that are not managed', () => {
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

    it('an external AgentOS does not hide a taken Factory port, and the other way round', async () => {
      const block = portsFor(local[0] as number)
      await expect(
        chooseStackPorts(
          request({
            profile: 'local',
            managed: { agentos: false, factory: true },
            isFree: freeExcept([block.agentos, block.bolt, block.factory]),
          })
        )
      ).rejects.toThrow(`Port ${block.factory} is already in use`)
      await expect(
        chooseStackPorts(
          request({
            profile: 'local',
            managed: { agentos: true, factory: false },
            isFree: freeExcept([block.factory, block.bolt]),
          })
        )
      ).rejects.toThrow(`Port ${block.bolt} is already in use`)
    })
  })

  describe('explicit choices', () => {
    it('CODAY_PORT_BASE: the block is used when free, whatever the profile', async () => {
      const base = prod[5] as number
      const { base: chosen, ports } = await chooseStackPorts(request({ profile: 'dev', explicitBase: base }))
      expect(chosen).toBe(base)
      expect(ports.express).toBe(role(base, 'server'))
    })

    it('CODAY_PORT_BASE: strict on that block, the default block is not probed nor used', async () => {
      const base = prod[5] as number
      await expect(
        chooseStackPorts(request({ profile: 'prod', explicitBase: base, isFree: freeExcept([role(base, 'agentos')]) }))
      ).rejects.toThrow(`Port ${role(base, 'agentos')} is already in use`)
      // The default block being taken does not matter once another block is chosen.
      const { base: chosen } = await chooseStackPorts(
        request({ profile: 'prod', explicitBase: base, isFree: freeExcept(blockOf(prod[0] as number)) })
      )
      expect(chosen).toBe(base)
    })

    it('--port / PORT: Express is on it, the services keep the ports of the default block', async () => {
      const { base, ports } = await chooseStackPorts(request({ profile: 'prod', explicitExpress: 8123 }))
      expect(ports.express).toBe(8123)
      expect(ports.agentos).toBe(role(prod[0] as number, 'agentos'))
      expect(base).toBe(prod[0])
    })

    it('--port / PORT taken: fails with the message of the taken port', async () => {
      await expect(
        chooseStackPorts(request({ profile: 'prod', explicitExpress: 8123, isFree: freeExcept([8123]) }))
      ).rejects.toThrow('Port 8123 is already in use')
    })

    it('--port / PORT free but the default Express port taken: no problem, that port is not used', async () => {
      const { ports } = await chooseStackPorts(
        request({ profile: 'prod', explicitExpress: 8123, isFree: freeExcept([role(prod[0] as number, 'server')]) })
      )
      expect(ports.express).toBe(8123)
    })

    it('--port / PORT equal to a port of the block used by a service: fails (two services, one port)', async () => {
      const forced = role(prod[0] as number, 'agentos')
      await expect(chooseStackPorts(request({ profile: 'prod', explicitExpress: forced }))).rejects.toThrow(
        `Port ${forced} would be used by two services`
      )
    })

    it('CODAY_*_PORT of a managed service taken: fails', async () => {
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
  })
})

describe('stackRequestFromEnv', () => {
  it('derives profile and explicit choices from the environment', () => {
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
