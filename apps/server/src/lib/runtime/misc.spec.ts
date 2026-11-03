import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { inspectJava, javaInstallHint, parseJavaVersionOutput } from './java'
import { agentosDataDir } from './paths'
import { decideProfile } from './profile'
import { pickPorts } from './ports'
import { initialState, mergeState, overallDownload, readState, updateState } from './state'
import {
  agentosDatabaseExists,
  isValidEmail,
  markSetupDone,
  normalizeEmail,
  readIdentityEmail,
  readWebSetup,
  saveIdentity,
} from './web-setup'
import { COMMANDS, parseCommand } from './command-names'

let configDir: string
beforeEach(() => {
  configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'runtime-'))
})
afterEach(() => fs.rmSync(configDir, { recursive: true, force: true }))

describe('decideProfile', () => {
  const base = () => ({ auth: false, configDir })

  it('is laptop on a fresh install', () => {
    expect(decideProfile(base())).toMatchObject({ profile: 'laptop', setupEnabled: true })
  })

  it.each([
    ['--auth deployments', { auth: true }],
    ['an external AgentOS', { agentosUrl: 'http://agentos:8124' }],
    ['an imposed AGENTOS_EXTERNAL_USERID', { externalUserId: 'service' }],
  ])('stays managed (no wizard) for %s', (_label, extra) => {
    expect(decideProfile({ ...base(), ...extra })).toMatchObject({ profile: 'managed', setupEnabled: false })
  })

  it('keeps an existing install without wizard identity on the pre-wizard behaviour', () => {
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    expect(decideProfile(base())).toMatchObject({ profile: 'managed', setupEnabled: false })
  })

  it('goes laptop once the wizard identity exists, even with data', () => {
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    saveIdentity(configDir, 'me@acme.com')
    expect(decideProfile(base()).profile).toBe('laptop')
  })
})

describe('state', () => {
  it('merges ports and downloads one level deep and stamps updatedAt', () => {
    const t = new Date('2030-01-01T00:00:00Z')
    const merged = mergeState(
      { ...initialState('nx', 'laptop'), ports: { express: 1 }, downloads: { agentos: { state: 'running' } } },
      { ports: { agentos: 2 }, downloads: { factory: { state: 'done' } } },
      () => t
    )
    expect(merged.ports).toEqual({ express: 1, agentos: 2 })
    expect(merged.downloads).toEqual({ agentos: { state: 'running' }, factory: { state: 'done' } })
    expect(merged.updatedAt).toBe(t.toISOString())
  })

  it('lets each writer update its own keys without clobbering the others', () => {
    updateState(configDir, { ports: { express: 1 } }, initialState('nx', 'laptop'))
    updateState(configDir, { downloads: { agentos: { state: 'running', percent: 10 } } })
    updateState(configDir, { downloads: { factory: { state: 'done' } } })
    expect(readState(configDir)).toMatchObject({
      ports: { express: 1 },
      downloads: { agentos: { state: 'running', percent: 10 }, factory: { state: 'done' } },
    })
  })

  it('combines downloads: done only when all done, error wins', () => {
    expect(overallDownload({})).toEqual({ state: 'idle' })
    expect(overallDownload({ agentos: { state: 'done' }, factory: { state: 'done' } })).toEqual({
      state: 'done',
      percent: 100,
    })
    expect(overallDownload({ agentos: { state: 'done' } })).toMatchObject({ state: 'done' })
    expect(
      overallDownload({ agentos: { state: 'running', percent: 5 }, factory: { state: 'error', message: 'x' } })
    ).toEqual({
      state: 'error',
      message: 'x',
    })
  })
})

describe('web-setup', () => {
  it('validates and normalises emails', () => {
    expect(isValidEmail('me@acme.com')).toBe(true)
    for (const bad of ['', 'me', 'me@', '@acme.com', 'a b@c.d', 42, undefined]) expect(isValidEmail(bad)).toBe(false)
    expect(normalizeEmail('  Me@Acme.COM ')).toBe('me@acme.com')
  })

  it('persists the identity and the done flag without ever storing keys', () => {
    saveIdentity(configDir, 'Me@Acme.com')
    expect(readIdentityEmail(configDir)).toBe('me@acme.com')
    markSetupDone(configDir, () => new Date('2030-01-01T00:00:00Z'))
    const raw = fs.readFileSync(path.join(configDir, 'web-setup.json'), 'utf8')
    expect(JSON.parse(raw)).toEqual({
      identity: { email: 'me@acme.com' },
      setupDone: true,
      setupDoneAt: '2030-01-01T00:00:00.000Z',
    })
    expect(readWebSetup(configDir).setupDone).toBe(true)
  })

  it('sees the AgentOS database once AgentOS created it, not before', () => {
    expect(agentosDatabaseExists(configDir)).toBe(false)
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    expect(agentosDatabaseExists(configDir)).toBe(false)
    fs.writeFileSync(path.join(agentosDataDir(configDir), 'neostore'), '')
    expect(agentosDatabaseExists(configDir)).toBe(true)
  })
})

describe('java', () => {
  it('parses the first line of java --version', () => {
    expect(parseJavaVersionOutput('openjdk 25 2025-09-16\nOpenJDK Runtime')).toMatchObject({ major: 25 })
    expect(parseJavaVersionOutput('openjdk 21.0.3 2024-04-16')).toMatchObject({ major: 21 })
    expect(parseJavaVersionOutput('garbage')).toBeNull()
  })

  it('classifies ok / too-old / missing with an install hint', () => {
    expect(inspectJava(() => 'openjdk 25 2025-09-16', {})).toMatchObject({ state: 'ok', major: 25 })
    expect(inspectJava(() => 'openjdk 21.0.3 2024-04-16', {}, 'darwin')).toMatchObject({
      state: 'too-old',
      hint: expect.stringContaining('brew install'),
    })
    expect(
      inspectJava(
        () => {
          throw new Error('ENOENT')
        },
        {},
        'linux'
      )
    ).toMatchObject({ state: 'missing', hint: expect.stringContaining('adoptium') })
  })

  it('uses JAVA_HOME when set', () => {
    const bins: string[] = []
    inspectJava((bin) => (bins.push(bin), 'openjdk 25 2025'), { JAVA_HOME: '/jdk' })
    expect(bins[0]).toBe(path.join('/jdk', 'bin', 'java'))
  })

  it('has a hint per platform', () => {
    expect(javaInstallHint('win32')).toContain('winget')
  })
})

describe('pickPorts', () => {
  const identityFind = async (start: number) => start

  it('prefers the packaged-mode ports: AgentOS 8224, Bolt 7788, Factory 8241', async () => {
    expect(await pickPorts(3000, identityFind)).toEqual({
      express: 3000,
      agentos: 8224,
      agentosBolt: 7788,
      factory: 8241,
    })
  })

  it('never prefers the dev ports (AgentOS 8124, Factory 8141, Bolt 7688), reserved for dev mode', async () => {
    const ports = await pickPorts(3000, identityFind)
    expect([ports.agentos, ports.factory, ports.agentosBolt]).not.toContain(8124)
    expect([ports.agentos, ports.factory, ports.agentosBolt]).not.toContain(8141)
    expect([ports.agentos, ports.factory, ports.agentosBolt]).not.toContain(7688)
  })

  it('leaves the dev ports alone: a dev stack on 8124/8141/7688 changes nothing', async () => {
    const busy = new Set([8124, 8141, 7688])
    const find = async (start: number) => {
      let port = start
      while (busy.has(port)) port++
      return port
    }
    expect(await pickPorts(3000, find)).toEqual({ express: 3000, agentos: 8224, agentosBolt: 7788, factory: 8241 })
  })

  it('falls back to the next free port when the preferred one is taken', async () => {
    const busy = new Set([8224, 7788])
    const find = async (start: number) => {
      let port = start
      while (busy.has(port)) port++
      return port
    }
    expect(await pickPorts(3000, find)).toEqual({ express: 3000, agentos: 8225, agentosBolt: 7789, factory: 8241 })
  })

  it('never gives two services the same port (Express on the AgentOS default)', async () => {
    const ports = await pickPorts(8224, identityFind)
    expect(ports).toEqual({ express: 8224, agentos: 8225, agentosBolt: 7788, factory: 8241 })
    expect(new Set(Object.values(ports)).size).toBe(4)
  })

  it('never gives two services the same port (Express on the Factory default)', async () => {
    const ports = await pickPorts(8241, identityFind)
    expect(ports).toEqual({ express: 8241, agentos: 8224, agentosBolt: 7788, factory: 8242 })
    expect(new Set(Object.values(ports)).size).toBe(4)
  })

  it('bumps past a port already assigned to another service', async () => {
    // Express on 7788: Bolt cannot take it; AgentOS on 8224 and Factory on 8241 are unaffected.
    const ports = await pickPorts(7788, identityFind)
    expect(ports).toEqual({ express: 7788, agentos: 8224, agentosBolt: 7789, factory: 8241 })
    expect(new Set(Object.values(ports)).size).toBe(4)
  })
})

describe('parseCommand', () => {
  it('recognises the commands and ignores options and unknown words', () => {
    expect(parseCommand(['agentos', '--configDir', '/x'])).toBe('agentos')
    expect(parseCommand(['status', '--no-open'])).toBe('status')
    expect(COMMANDS.every((c) => parseCommand([c]) === c)).toBe(true)
    expect(parseCommand(['--auth'])).toBeUndefined()
    expect(parseCommand(['whatever'])).toBeUndefined()
    expect(parseCommand([])).toBeUndefined()
  })
})
