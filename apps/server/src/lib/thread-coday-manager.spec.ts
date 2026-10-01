import { afterEach, describe, expect, it, jest } from '@jest/globals'
import type { CodayLogger, CodayOptions } from '@coday/model'

jest.mock('@coday/core', () => ({ Coday: class {} }))
jest.mock('@coday/integrations-ai', () => ({}))
jest.mock('@coday/service', () => ({}))
jest.mock('@coday/mcp', () => ({}))
jest.mock('@coday/agent', () => ({}))
jest.mock('./thread-post-processor', () => ({ ThreadPostProcessor: class {} }))
jest.mock('./log', () => ({ debugLog: jest.fn() }))

import { ThreadCodayInstance } from './thread-coday-manager'

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function backgroundInstance(run: () => Promise<void>): ThreadCodayInstance {
  const instance = new ThreadCodayInstance(
    'thread-1',
    'Forge',
    'scheduler',
    {} as CodayOptions,
    {} as CodayLogger,
    {} as never,
    {} as never,
    {} as never,
    {} as never,
    () => undefined,
    undefined,
    () => undefined,
    () => false
  )
  const internals = instance as unknown as { prepareCoday: () => void; coday: { run: () => Promise<void>; kill: () => Promise<void> } }
  internals.prepareCoday = () => undefined
  internals.coday = { run, kill: async () => undefined }
  return instance
}

describe('ThreadCodayInstance.runOneshot', () => {
  const instances: ThreadCodayInstance[] = []
  let consoleError: jest.Spied<typeof console.error>

  afterEach(async () => {
    await Promise.all(instances.splice(0).map((instance) => instance.cleanup()))
    consoleError.mockRestore()
  })

  it('does not settle before the background run completes', async () => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const run = deferred<void>()
    const instance = backgroundInstance(() => run.promise)
    instances.push(instance)
    let settled = false

    const lifecycle = instance.runOneshot().then(() => {
      settled = true
    })

    await Promise.resolve()
    expect(settled).toBe(false)
    expect(consoleError).not.toHaveBeenCalled()

    run.resolve()
    await lifecycle
    expect(settled).toBe(true)
  })

  it('settles after reporting a background failure', async () => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const run = deferred<void>()
    const failure = new Error('agent failed')
    const instance = backgroundInstance(() => run.promise)
    instances.push(instance)

    const lifecycle = instance.runOneshot()
    run.reject(failure)

    await expect(lifecycle).resolves.toBeUndefined()
    expect(consoleError).toHaveBeenCalledWith('Oneshot run failed for thread thread-1:', failure)
  })
})
