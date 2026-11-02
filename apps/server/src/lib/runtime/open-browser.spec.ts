import { appUrl, isFirstRun, openTiming } from './app-url'
import {
  agentosHealthUrl,
  BrowserOpenInput,
  openBrowser,
  openCommand,
  OpenSpawn,
  openWhenReady,
  planBrowserOpen,
  waitForAgentosHealthy,
} from './open-browser'

const ports = { express: 3100 }

describe('appUrl', () => {
  it('is /welcome while a laptop first run is pending', () => {
    expect(appUrl({ profile: 'laptop', ports }, {})).toBe('http://localhost:3100/welcome')
    expect(appUrl({ profile: 'laptop', ports }, { identity: { email: 'a@b.co' } })).toBe(
      'http://localhost:3100/welcome'
    )
  })

  it('is the root once the setup is done', () => {
    expect(appUrl({ profile: 'laptop', ports }, { setupDone: true })).toBe('http://localhost:3100/')
  })

  it('is the root in the managed profile, whatever the setup', () => {
    expect(appUrl({ profile: 'managed', ports }, {})).toBe('http://localhost:3100/')
  })

  it('is undefined until the Express port is known', () => {
    expect(appUrl(undefined, {})).toBeUndefined()
    expect(appUrl({ profile: 'laptop', ports: {} }, {})).toBeUndefined()
  })
})

describe('isFirstRun / openTiming', () => {
  it('opens at once only while the laptop wizard is pending (it needs no AgentOS)', () => {
    expect(isFirstRun({ profile: 'laptop' }, {})).toBe(true)
    expect(openTiming({ profile: 'laptop' }, {})).toBe('now')
    expect(openTiming({ profile: 'laptop' }, { identity: { email: 'a@b.co' } })).toBe('now')
  })

  it('waits for the backend on a resume and in the managed profile (the app calls AgentOS straight away)', () => {
    expect(openTiming({ profile: 'laptop' }, { setupDone: true })).toBe('when-backend-ready')
    expect(openTiming({ profile: 'managed' }, {})).toBe('when-backend-ready')
    expect(openTiming(undefined, {})).toBe('when-backend-ready')
  })

  it('agrees with the URL: /welcome exactly when the timing is now', () => {
    for (const profile of ['laptop', 'managed'] as const) {
      for (const setup of [{}, { setupDone: true }]) {
        const welcome = appUrl({ profile, ports }, setup)?.endsWith('/welcome')
        expect(welcome).toBe(openTiming({ profile }, setup) === 'now')
      }
    }
  })
})

describe('planBrowserOpen', () => {
  const input = (patch: Partial<BrowserOpenInput> = {}): BrowserOpenInput => ({
    noOpenFlag: false,
    env: {},
    auth: false,
    host: '127.0.0.1',
    state: { profile: 'laptop', ports },
    setup: {},
    ...patch,
  })

  it('opens /welcome at once on a first run, and the root once the backend is ready afterwards', () => {
    expect(planBrowserOpen(input())).toEqual({ url: 'http://localhost:3100/welcome', timing: 'now' })
    expect(planBrowserOpen(input({ setup: { setupDone: true } }))).toEqual({
      url: 'http://localhost:3100/',
      timing: 'when-backend-ready',
    })
  })

  it.each([
    ['--no-open', { noOpenFlag: true }, '--no-open'],
    ['CODAY_NO_OPEN=1', { env: { CODAY_NO_OPEN: '1' } }, 'CODAY_NO_OPEN'],
    ['CODAY_NO_OPEN=true', { env: { CODAY_NO_OPEN: 'true' } }, 'CODAY_NO_OPEN'],
    ['CI', { env: { CI: 'true' } }, 'CI'],
    ['CI=1', { env: { CI: '1' } }, 'CI'],
    ['--auth', { auth: true, host: '0.0.0.0' }, '--auth'],
    ['a non-loopback host', { host: '0.0.0.0' }, 'loopback'],
    ['a LAN address', { host: '192.168.1.5' }, 'loopback'],
  ] as Array<[string, Partial<BrowserOpenInput>, string]>)('does not open with %s', (_label, patch, reason) => {
    const plan = planBrowserOpen(input(patch))
    expect(plan).toEqual({ skip: expect.stringContaining(reason) })
  })

  it('still opens with CODAY_NO_OPEN=0 or CI=false, and on localhost / ::1 hosts', () => {
    expect(planBrowserOpen(input({ env: { CODAY_NO_OPEN: '0' } }))).toHaveProperty('url')
    expect(planBrowserOpen(input({ env: { CI: 'false' } }))).toHaveProperty('url')
    expect(planBrowserOpen(input({ host: 'localhost' }))).toHaveProperty('url')
    expect(planBrowserOpen(input({ host: '::1' }))).toHaveProperty('url')
  })

  it('skips when the port is unknown', () => {
    expect(planBrowserOpen(input({ state: { profile: 'laptop', ports: {} } }))).toHaveProperty('skip')
  })
})

describe('agentosHealthUrl', () => {
  it('uses the external AgentOS as the proxy does, whatever the managed port', () => {
    expect(agentosHealthUrl('http://agentos.acme.com:8124/', { ports: { agentos: 8124 } })).toBe(
      'http://agentos.acme.com:8124/management/health'
    )
  })

  it('uses the managed port from state.json on loopback', () => {
    expect(agentosHealthUrl(undefined, { ports: { agentos: 8125 } })).toBe('http://127.0.0.1:8125/management/health')
  })

  it('is undefined until provisioning chose the port', () => {
    expect(agentosHealthUrl(undefined, undefined)).toBeUndefined()
    expect(agentosHealthUrl(undefined, { ports: {} })).toBeUndefined()
  })
})

describe('waitForAgentosHealthy', () => {
  const noSleep = async () => undefined

  it('resolves true once the probe turns true, re-reading the URL at each poll', async () => {
    const urls: Array<string | undefined> = [undefined, 'http://127.0.0.1:1/h', 'http://127.0.0.1:1/h']
    const probed: string[] = []
    let polls = 0
    const ready = await waitForAgentosHealthy({
      healthUrl: () => urls[polls++],
      probe: async (url) => (probed.push(url), probed.length >= 2),
      sleep: noSleep,
    })
    expect(ready).toBe(true)
    expect(probed).toHaveLength(2) // nothing probed while the port was unknown
  })

  it('resolves false at the deadline', async () => {
    let clock = 0
    const ready = await waitForAgentosHealthy({
      healthUrl: () => 'http://127.0.0.1:1/h',
      probe: async () => false,
      timeoutMs: 1_000,
      now: () => clock,
      sleep: async (ms) => void (clock += ms),
      intervalMs: 400,
    })
    expect(ready).toBe(false)
  })

  it('resolves false when the signal aborts, without waiting for the deadline', async () => {
    const controller = new AbortController()
    const probe = jest.fn(async () => false)
    const ready = await waitForAgentosHealthy({
      healthUrl: () => 'http://127.0.0.1:1/h',
      probe,
      signal: controller.signal,
      sleep: async () => controller.abort(),
    })
    expect(ready).toBe(false)
    expect(probe).toHaveBeenCalledTimes(1)
  })
})

describe('openWhenReady', () => {
  const url = 'http://localhost:3100/'
  const deps = (patch: Partial<Parameters<typeof openWhenReady>[1]> = {}) => ({
    open: jest.fn(),
    waitForBackend: jest.fn(async () => true),
    log: jest.fn(),
    ...patch,
  })

  it('opens a first run immediately, without probing or waiting', async () => {
    const d = deps()
    expect(await openWhenReady({ url: 'http://localhost:3100/welcome', timing: 'now' }, d)).toBe(true)
    expect(d.open).toHaveBeenCalledWith('http://localhost:3100/welcome')
    expect(d.waitForBackend).not.toHaveBeenCalled()
    expect(d.log).not.toHaveBeenCalled()
  })

  it('on a resume, says it waits, and opens only AFTER the backend answered', async () => {
    const order: string[] = []
    let release: (ready: boolean) => void = () => undefined
    const d = deps({
      waitForBackend: () => new Promise<boolean>((resolve) => (release = resolve)),
      open: jest.fn(() => void order.push('open')),
      log: jest.fn(() => void order.push('log')),
    })
    const done = openWhenReady({ url, timing: 'when-backend-ready' }, d)
    await Promise.resolve()
    expect(d.open).not.toHaveBeenCalled()
    expect(d.log).toHaveBeenCalledWith(`Waiting for AgentOS before opening the browser (${url})...`)

    release(true)
    expect(await done).toBe(true)
    expect(order).toEqual(['log', 'open'])
    expect(d.open).toHaveBeenCalledWith(url)
  })

  it('on timeout, does not open and prints the URL to open by hand', async () => {
    const d = deps({ waitForBackend: jest.fn(async () => false) })
    expect(await openWhenReady({ url, timing: 'when-backend-ready' }, d)).toBe(false)
    expect(d.open).not.toHaveBeenCalled()
    expect(d.log).toHaveBeenLastCalledWith(`AgentOS is not healthy yet: open ${url} yourself when it is.`)
  })

  it('does not open when the signal aborted while waiting, and says nothing more', async () => {
    const controller = new AbortController()
    const d = deps({
      signal: controller.signal,
      waitForBackend: jest.fn(async () => {
        controller.abort()
        return true
      }),
    })
    expect(await openWhenReady({ url, timing: 'when-backend-ready' }, d)).toBe(false)
    expect(d.open).not.toHaveBeenCalled()
    expect(d.log).toHaveBeenCalledTimes(1) // only the 'Waiting for AgentOS' line
  })

  it('does not even start waiting when already aborted', async () => {
    const controller = new AbortController()
    controller.abort()
    const d = deps({ signal: controller.signal })
    expect(await openWhenReady({ url, timing: 'when-backend-ready' }, d)).toBe(false)
    expect(d.waitForBackend).not.toHaveBeenCalled()
    expect(d.log).not.toHaveBeenCalled()
  })
})

describe('skip conditions short-circuit before any waiting', () => {
  it.each([
    ['--no-open', { noOpenFlag: true }],
    ['CODAY_NO_OPEN', { env: { CODAY_NO_OPEN: '1' } }],
    ['CI', { env: { CI: 'true' } }],
    ['--auth', { auth: true }],
    ['a non-loopback host', { host: '0.0.0.0' }],
  ] as Array<[string, Partial<BrowserOpenInput>]>)(
    '%s: a plan without url nor timing, nothing to wait for',
    (_l, patch) => {
      const plan = planBrowserOpen({
        noOpenFlag: false,
        env: {},
        auth: false,
        host: '127.0.0.1',
        state: { profile: 'laptop', ports },
        setup: { setupDone: true }, // a resume: would wait if it were not skipped
        ...patch,
      })
      expect(plan).toHaveProperty('skip')
      expect(plan).not.toHaveProperty('timing')
    }
  )
})

describe('openCommand', () => {
  const url = 'http://localhost:3100/welcome'
  it('picks the launcher of each platform', () => {
    expect(openCommand(url, 'darwin')).toEqual({ command: 'open', args: [url] })
    expect(openCommand(url, 'win32')).toEqual({ command: 'cmd', args: ['/c', 'start', '', url] })
    expect(openCommand(url, 'linux')).toEqual({ command: 'xdg-open', args: [url] })
    expect(openCommand(url, 'freebsd')).toEqual({ command: 'xdg-open', args: [url] })
  })
})

describe('openBrowser', () => {
  const url = 'http://localhost:3100/'

  const fakeSpawn = () => {
    const calls: Array<{ command: string; args: string[]; options: object }> = []
    const handlers: Array<(e: Error) => void> = []
    const unref = jest.fn()
    const spawnFn: OpenSpawn = (command, args, options) => {
      calls.push({ command, args, options })
      return { on: (_event, listener) => void handlers.push(listener), unref }
    }
    return { spawnFn, calls, handlers, unref }
  }

  it('spawns detached with ignored stdio, unref()s and logs one line', () => {
    const { spawnFn, calls, unref } = fakeSpawn()
    const log = jest.fn()
    expect(openBrowser(url, { platform: 'darwin', spawnFn, log })).toBe(true)
    expect(calls).toEqual([{ command: 'open', args: [url], options: { detached: true, stdio: 'ignore' } }])
    expect(unref).toHaveBeenCalled()
    expect(log).toHaveBeenCalledTimes(1)
  })

  it('swallows an asynchronous spawn error (missing xdg-open) with one extra log line', () => {
    const { spawnFn, handlers } = fakeSpawn()
    const log = jest.fn()
    openBrowser(url, { platform: 'linux', spawnFn, log })
    expect(() => handlers.forEach((h) => h(new Error('spawn xdg-open ENOENT')))).not.toThrow()
    expect(log).toHaveBeenLastCalledWith(expect.stringContaining('ENOENT'))
    expect(log).toHaveBeenLastCalledWith(expect.stringContaining(url))
  })

  it('swallows a synchronous spawn failure', () => {
    const log = jest.fn()
    const spawnFn: OpenSpawn = () => {
      throw new Error('EACCES')
    }
    expect(openBrowser(url, { platform: 'linux', spawnFn, log })).toBe(false)
    expect(log).toHaveBeenCalledWith(expect.stringContaining('EACCES'))
  })
})
