import { appUrl } from './app-url'
import {
  agentosHealthUrl,
  BrowserOpenInput,
  openBrowser,
  openCommand,
  OpenSpawn,
  planBrowserOpen,
} from './open-browser'

const ports = { express: 3100 }

describe('appUrl', () => {
  it('is always /welcome: the wizard, or the loading page, whatever the profile', () => {
    expect(appUrl({ ports })).toBe('http://localhost:3100/welcome')
  })

  it('is undefined until the Express port is known', () => {
    expect(appUrl(undefined)).toBeUndefined()
    expect(appUrl({ ports: {} })).toBeUndefined()
  })
})

describe('planBrowserOpen', () => {
  const input = (patch: Partial<BrowserOpenInput> = {}): BrowserOpenInput => ({
    noOpenFlag: false,
    env: {},
    auth: false,
    host: '127.0.0.1',
    state: { ports },
    ...patch,
  })

  it('opens /welcome, with nothing to wait for', () => {
    expect(planBrowserOpen(input())).toEqual({ url: 'http://localhost:3100/welcome' })
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
    expect(planBrowserOpen(input({ state: { ports: {} } }))).toHaveProperty('skip')
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
  const url = 'http://localhost:3100/welcome'

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
