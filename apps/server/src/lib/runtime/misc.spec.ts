import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { findJavaHome, inspectJava, javaHomeHint, javaInstallHint, parseJavaVersionOutput } from './java'
import { agentosDataDir } from './paths'
import { borrowsRuntime, decideProfile, parseForcedProfile } from './profile'
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

  it('is unmanaged on a fresh install', () => {
    expect(decideProfile(base())).toMatchObject({ profile: 'unmanaged', setupEnabled: true })
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

  it('goes unmanaged once the wizard identity exists, even with data', () => {
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    saveIdentity(configDir, 'me@acme.com')
    expect(decideProfile(base()).profile).toBe('unmanaged')
  })

  describe('CODAY_PROFILE', () => {
    it('forces unmanaged over an external AgentOS, without the wizard', () => {
      expect(
        decideProfile({ ...base(), agentosUrl: 'http://localhost:8224', forcedProfile: 'unmanaged' })
      ).toMatchObject({
        profile: 'unmanaged',
        setupEnabled: false,
      })
    })

    it('forces unmanaged with the wizard when nothing else prevents it', () => {
      fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
      expect(decideProfile({ ...base(), forcedProfile: 'Unmanaged' })).toMatchObject({
        profile: 'unmanaged',
        setupEnabled: true,
      })
    })

    it('forces managed on a fresh install', () => {
      expect(decideProfile({ ...base(), forcedProfile: 'managed' })).toMatchObject({
        profile: 'managed',
        setupEnabled: false,
      })
    })

    it('ignores an unknown value', () => {
      expect(parseForcedProfile('whatever')).toBeUndefined()
      expect(parseForcedProfile(undefined)).toBeUndefined()
      expect(decideProfile({ ...base(), forcedProfile: 'whatever' })).toMatchObject({ profile: 'unmanaged' })
    })
  })
})

describe('borrowsRuntime', () => {
  const base = () => ({ auth: false, configDir })

  it('borrows the runtime of an external AgentOS on a provisioned configDir, in-process only', () => {
    const external = { ...base(), agentosUrl: 'http://localhost:8224' }
    expect(borrowsRuntime(external, true, 'in-process')).toBe(true)
    expect(borrowsRuntime(external, false, 'in-process')).toBe(false)
    expect(borrowsRuntime(base(), true, 'in-process')).toBe(false)
  })

  it('never borrows in Nx mode: the server task reads its Express port from what provision wrote', () => {
    expect(borrowsRuntime({ ...base(), agentosUrl: 'http://localhost:8224' }, true, 'nx')).toBe(false)
  })
})

describe('state', () => {
  it('merges ports and downloads one level deep and stamps updatedAt', () => {
    const t = new Date('2030-01-01T00:00:00Z')
    const merged = mergeState(
      { ...initialState('nx', 'unmanaged'), ports: { express: 1 }, downloads: { agentos: { state: 'running' } } },
      { ports: { agentos: 2 }, downloads: { factory: { state: 'done' } } },
      () => t
    )
    expect(merged.ports).toEqual({ express: 1, agentos: 2 })
    expect(merged.downloads).toEqual({ agentos: { state: 'running' }, factory: { state: 'done' } })
    expect(merged.updatedAt).toBe(t.toISOString())
  })

  it('lets each writer update its own keys without clobbering the others', () => {
    updateState(configDir, { ports: { express: 1 } }, initialState('nx', 'unmanaged'))
    updateState(configDir, { downloads: { agentos: { state: 'running', percent: 10 } } })
    updateState(configDir, { downloads: { factory: { state: 'done' } } })
    expect(readState(configDir)).toMatchObject({
      ports: { express: 1 },
      downloads: { agentos: { state: 'running', percent: 10 }, factory: { state: 'done' } },
    })
  })

  it('remembers the port block of the last start next to the ports', () => {
    updateState(configDir, { ports: { express: 3020 }, portBlock: 3020 }, initialState('nx', 'unmanaged'))
    updateState(configDir, { downloads: { agentos: { state: 'running' } } })
    expect(readState(configDir)).toMatchObject({ ports: { express: 3020 }, portBlock: 3020 })
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

  describe('JAVA_HOME shadowing a usable JDK', () => {
    const noHome = () => undefined

    /** `run` per binary: JAVA_HOME holds an old JDK (or nothing), the PATH and /jdk25 hold a good one. */
    const runner =
      (versions: Record<string, string | undefined>) =>
      (bin: string): string => {
        const version = versions[bin]
        if (!version) throw new Error(`ENOENT: ${bin}`)
        return version
      }

    it('points at the PATH java instead of telling the user to install one', () => {
      const status = inspectJava(
        runner({ java: 'openjdk 25 2025-09-16' }), // $JAVA_HOME/bin/java is absent
        { JAVA_HOME: '/old-jdk' },
        'darwin',
        noHome
      )
      expect(status.state).toBe('missing')
      expect(status).toMatchObject({ alternative: { version: 'openjdk 25 2025-09-16' } })
      expect(status.state !== 'ok' && status.hint).toContain('unset JAVA_HOME')
      expect(status.state !== 'ok' && status.hint).not.toContain('brew install')
    })

    it('gives the export command when only a lookup finds the JDK (macOS)', () => {
      const status = inspectJava(
        runner({
          [path.join('/old-jdk', 'bin', 'java')]: 'openjdk 21.0.3 2024-04-16',
          [path.join('/jdk25', 'bin', 'java')]: 'openjdk 25 2025-09-16',
        }),
        { JAVA_HOME: '/old-jdk' },
        'darwin',
        () => '/jdk25'
      )
      expect(status).toMatchObject({ state: 'too-old', alternative: { home: '/jdk25' } })
      expect(status.state !== 'ok' && status.hint).toContain('/usr/libexec/java_home -v25')
    })

    it('still says to install when nothing usable exists anywhere', () => {
      const status = inspectJava(
        runner({ [path.join('/old-jdk', 'bin', 'java')]: 'openjdk 21.0.3 2024-04-16' }),
        { JAVA_HOME: '/old-jdk' },
        'darwin',
        noHome
      )
      expect(status).toMatchObject({ state: 'too-old' })
      expect(status.state === 'too-old' && status.alternative).toBeUndefined()
      expect(status.state !== 'ok' && status.hint).toContain('brew install')
    })

    it('does not look for an alternative when JAVA_HOME is not what failed', () => {
      const probed: string[] = []
      inspectJava((bin) => (probed.push(bin), 'openjdk 21.0.3 2024-04-16'), {}, 'linux', noHome)
      expect(probed).toEqual(['java'])
    })
  })

  it('names the JDK found and the variable to fix', () => {
    expect(javaHomeHint({ version: 'openjdk 25' }, '/old', 'win32')).toContain('Clear JAVA_HOME')
    expect(javaHomeHint({ version: 'openjdk 25', home: '/jdk25' }, '/old', 'win32')).toContain('/jdk25')
  })

  it('only asks the system for a JDK home on macOS', () => {
    expect(findJavaHome('linux', () => '/never')).toBeUndefined()
    expect(findJavaHome('darwin', () => '/jdk25\n')).toBe('/jdk25')
    expect(
      findJavaHome('darwin', () => {
        throw new Error('no such JDK')
      })
    ).toBeUndefined()
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
