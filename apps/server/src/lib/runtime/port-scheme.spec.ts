import scheme from './port-scheme.json'
import {
  blockBases,
  candidateBases,
  parsePortBase,
  PORT_BASES,
  PORT_BLOCK_SIZE,
  PORT_OFFSETS,
  portProfile,
  portsFor,
  profileBase,
} from './port-scheme'

describe('port scheme', () => {
  describe('table', () => {
    // The ONE place where the numbers are pinned as literals: changing port-scheme.json on purpose means changing this
    // table too (and the docs that show it: apps/web/README.md, agents/*.yaml). Everything else derives from the JSON.
    it('is the documented one', () => {
      expect(PORT_BASES).toEqual({ prod: 3000, local: 3010, dev: 4000 })
      expect(PORT_BLOCK_SIZE).toBe(10)
      expect(PORT_OFFSETS).toEqual({ server: 0, client: 1, factoryCockpit: 2, agentos: 5, factory: 6, bolt: 7 })
      expect(portsFor(PORT_BASES.prod)).toEqual({
        server: 3000,
        client: 3001,
        factoryCockpit: 3002,
        agentos: 3005,
        factory: 3006,
        bolt: 3007,
      })
      expect(portsFor(PORT_BASES.local).server).toBe(3010)
      expect(portsFor(PORT_BASES.dev)).toMatchObject({
        server: 4000,
        client: 4001,
        factoryCockpit: 4002,
        agentos: 4005,
      })
    })

    it('derives the bases from the JSON', () => {
      for (const profile of ['prod', 'local', 'dev'] as const) {
        expect(PORT_BASES[profile]).toBe(scheme.profiles[profile].base)
      }
    })

    it('never uses the macOS AirPlay Receiver ports (5000, 7000), whatever the profile or the block', () => {
      for (const profile of ['prod', 'local', 'dev'] as const) {
        for (const base of candidateBases(profile)) {
          expect(Object.values(portsFor(base))).not.toContain(5000)
          expect(Object.values(portsFor(base))).not.toContain(7000)
        }
      }
    })
  })

  describe('portsFor', () => {
    it('adds the same offsets to every base', () => {
      for (const base of Object.values(PORT_BASES)) {
        expect(portsFor(base)).toEqual({
          server: base + PORT_OFFSETS.server,
          client: base + PORT_OFFSETS.client,
          factoryCockpit: base + PORT_OFFSETS.factoryCockpit,
          agentos: base + PORT_OFFSETS.agentos,
          factory: base + PORT_OFFSETS.factory,
          bolt: base + PORT_OFFSETS.bolt,
        })
      }
    })

    it('keeps every role inside its block', () => {
      for (const offset of Object.values(PORT_OFFSETS)) expect(offset).toBeLessThan(PORT_BLOCK_SIZE)
    })
  })

  describe('blockBases', () => {
    it('lists block bases `step` apart (one block by default)', () => {
      expect(blockBases(4000, 3)).toEqual([4000, 4010, 4020])
      expect(blockBases(3000, 3, 20)).toEqual([3000, 3020, 3040])
      expect(blockBases(4000, 0)).toEqual([])
    })
  })

  describe('candidateBases', () => {
    it('prod 3000, 3020, 3040... and local 3010, 3030, 3050... interleave', () => {
      expect(candidateBases('prod', 4)).toEqual([3000, 3020, 3040, 3060])
      expect(candidateBases('local', 4)).toEqual([3010, 3030, 3050, 3070])
    })

    it('dev scans the whole of 4xxx, one block at a time', () => {
      expect(candidateBases('dev', 3)).toEqual([4000, 4010, 4020])
    })

    it.each(['prod', 'local', 'dev'] as const)(
      '%s starts with its own base and stays in its thousand (up to x990)',
      (profile) => {
        const bases = candidateBases(profile)
        const thousand = Math.floor(PORT_BASES[profile] / 1000) * 1000
        expect(bases[0]).toBe(PORT_BASES[profile])
        for (const base of bases) {
          expect(base).toBeGreaterThanOrEqual(thousand)
          // The whole block fits in the thousand: never straddles into the next one.
          expect(base + PORT_BLOCK_SIZE - 1).toBeLessThan(thousand + 1000)
        }
      }
    )

    it('local reaches 3990, the end of the thousand', () => {
      expect(candidateBases('local').at(-1)).toBe(3990)
      expect(candidateBases('prod').at(-1)).toBe(3980)
    })

    it('never lets a prod block and a local block overlap, whatever the block', () => {
      const taken = new Set<number>()
      for (const base of candidateBases('prod')) {
        for (let offset = 0; offset < PORT_BLOCK_SIZE; offset++) taken.add(base + offset)
      }
      for (const base of candidateBases('local')) {
        for (let offset = 0; offset < PORT_BLOCK_SIZE; offset++) expect(taken.has(base + offset)).toBe(false)
      }
    })

    it('never lets a packaged block overlap a dev block', () => {
      const dev = new Set(candidateBases('dev'))
      for (const profile of ['prod', 'local'] as const) {
        for (const base of candidateBases(profile)) expect(dev.has(base)).toBe(false)
      }
    })
  })

  describe('parsePortBase', () => {
    it('accepts a valid base', () => {
      expect(parsePortBase('3010')).toBe(3010)
      expect(parsePortBase('1024')).toBe(1024)
      expect(parsePortBase(String(65535 - PORT_BLOCK_SIZE + 1))).toBe(65535 - PORT_BLOCK_SIZE + 1)
    })

    it.each([undefined, '', 'abc', '4000.5', '-1', '0', '80', '1023', '65535', '70000'])('rejects %p', (value) => {
      expect(parsePortBase(value)).toBeUndefined()
    })
  })

  describe('portProfile', () => {
    it('is prod by default and dev with BUILD_ENV=development', () => {
      expect(portProfile({})).toBe('prod')
      expect(portProfile({ BUILD_ENV: 'development' })).toBe('dev')
      expect(portProfile({ BUILD_ENV: 'production' })).toBe('prod')
    })

    it('obeys CODAY_PORT_PROFILE (web:local says local), ignoring anything else', () => {
      expect(portProfile({ CODAY_PORT_PROFILE: 'local' })).toBe('local')
      expect(portProfile({ CODAY_PORT_PROFILE: 'local', BUILD_ENV: 'development' })).toBe('local')
      expect(portProfile({ CODAY_PORT_PROFILE: 'nope' })).toBe('prod')
    })
  })

  describe('profileBase', () => {
    it('is the first block of the profile', () => {
      expect(profileBase({})).toBe(PORT_BASES.prod)
      expect(profileBase({ BUILD_ENV: 'development' })).toBe(PORT_BASES.dev)
      expect(profileBase({ CODAY_PORT_PROFILE: 'local' })).toBe(PORT_BASES.local)
    })

    it('CODAY_PORT_BASE wins over the profile', () => {
      expect(profileBase({ CODAY_PORT_BASE: '3050' })).toBe(3050)
      expect(profileBase({ CODAY_PORT_BASE: '3050', BUILD_ENV: 'development' })).toBe(3050)
    })

    it('ignores an invalid CODAY_PORT_BASE', () => {
      expect(profileBase({ CODAY_PORT_BASE: 'nope' })).toBe(PORT_BASES.prod)
      expect(profileBase({ CODAY_PORT_BASE: '12', BUILD_ENV: 'development' })).toBe(PORT_BASES.dev)
    })
  })
})
