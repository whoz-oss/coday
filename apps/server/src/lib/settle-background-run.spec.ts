import { afterEach, describe, expect, it, jest } from '@jest/globals'
import { settleBackgroundRun } from './settle-background-run'

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('settleBackgroundRun', () => {
  afterEach(() => {
    jest.clearAllMocks()
  })

  it('does not settle before the background run completes', async () => {
    const run = deferred<void>()
    const reportError = jest.fn()
    let settled = false

    const lifecycle = settleBackgroundRun(() => run.promise, reportError).then(() => {
      settled = true
    })

    await Promise.resolve()
    expect(settled).toBe(false)
    expect(reportError).not.toHaveBeenCalled()

    run.resolve()
    await lifecycle
    expect(settled).toBe(true)
  })

  it('settles after reporting a background failure', async () => {
    const run = deferred<void>()
    const failure = new Error('agent failed')
    const reportError = jest.fn()

    const lifecycle = settleBackgroundRun(() => run.promise, reportError)
    run.reject(failure)

    await expect(lifecycle).resolves.toBeUndefined()
    expect(reportError).toHaveBeenCalledWith(failure)
  })
})
