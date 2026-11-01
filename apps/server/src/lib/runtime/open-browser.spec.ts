import type { spawn } from 'child_process'
import { openBrowser, openCommand } from './open-browser'

const URL = 'http://localhost:3000/'

interface FakeChild {
  on: jest.Mock
  unref: jest.Mock
}

const fakeSpawn = (child: FakeChild = { on: jest.fn(), unref: jest.fn() }) => {
  const fn = jest.fn(() => child)
  return { fn, child, spawnFn: fn as unknown as typeof spawn }
}

describe('openCommand', () => {
  it('uses open on macOS, xdg-open on Linux and cmd start on Windows', () => {
    expect(openCommand(URL, 'darwin')).toEqual({ command: 'open', args: [URL] })
    expect(openCommand(URL, 'linux')).toEqual({ command: 'xdg-open', args: [URL] })
    expect(openCommand(URL, 'win32')).toEqual({ command: 'cmd', args: ['/c', 'start', '', URL] })
  })

  it('knows no opener for other platforms', () => {
    expect(openCommand(URL, 'aix')).toBeUndefined()
  })
})

describe('openBrowser', () => {
  it('spawns the opener detached, silent and unref-ed', () => {
    const { fn, child, spawnFn } = fakeSpawn()
    expect(openBrowser(URL, { platform: 'linux', spawnFn })).toBe(true)
    expect(fn).toHaveBeenCalledWith('xdg-open', [URL], { detached: true, stdio: 'ignore', windowsHide: true })
    expect(child.unref).toHaveBeenCalled()
  })

  it('swallows a synchronous spawn failure and reports one line', () => {
    const onError = jest.fn()
    const spawnFn = jest.fn(() => {
      throw new Error('boom')
    }) as unknown as typeof spawn
    expect(openBrowser(URL, { platform: 'darwin', spawnFn, onError })).toBe(false)
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0]?.[0]).toContain('boom')
  })

  it('swallows an asynchronous spawn error (no xdg-open) and reports it once', () => {
    const onError = jest.fn()
    const { child, spawnFn } = fakeSpawn()
    expect(openBrowser(URL, { platform: 'linux', spawnFn, onError })).toBe(true)
    const listener = child.on.mock.calls.find(([event]) => event === 'error')?.[1] as (e: Error) => void
    expect(listener).toBeDefined()
    listener(new Error('spawn xdg-open ENOENT'))
    listener(new Error('again'))
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0]?.[0]).toContain('ENOENT')
  })

  it('does not spawn anything on an unknown platform', () => {
    const onError = jest.fn()
    const { fn, spawnFn } = fakeSpawn()
    expect(openBrowser(URL, { platform: 'aix', spawnFn, onError })).toBe(false)
    expect(fn).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledTimes(1)
  })
})
