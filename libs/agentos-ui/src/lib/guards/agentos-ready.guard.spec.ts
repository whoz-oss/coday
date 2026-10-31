import { isAgentosReady, waitForAgentos } from './agentos-ready.guard'

/** Plain object responses: the jsdom test environment has no `Response` global. */
const answer = (body: unknown, status = 200): typeof fetch =>
  (async () => ({ ok: status >= 200 && status < 300, status, json: async () => body })) as unknown as typeof fetch

describe('isAgentosReady', () => {
  it('is ready when the server reports AgentOS done', async () => {
    expect(await isAgentosReady(answer({ steps: { agentos: { state: 'done' } } }))).toBe(true)
  })

  it.each(['pending', 'running', 'blocked', 'error'])('is not ready while AgentOS is %s', async (state) => {
    expect(await isAgentosReady(answer({ steps: { agentos: { state } } }))).toBe(false)
  })

  it('never blocks when the setup API is missing or broken (external AgentOS, older server)', async () => {
    expect(await isAgentosReady(answer({}, 404))).toBe(true)
    expect(await isAgentosReady(answer({}, 500))).toBe(true)
    expect(await isAgentosReady((async () => Promise.reject(new Error('network'))) as typeof fetch)).toBe(true)
    expect(await isAgentosReady(answer({}))).toBe(true)
  })
})

describe('waitForAgentos', () => {
  it('polls until AgentOS is ready', async () => {
    let calls = 0
    const fetchFn = (async () => {
      calls++
      return {
        ok: true,
        status: 200,
        json: async () => ({ steps: { agentos: { state: calls < 3 ? 'running' : 'done' } } }),
      }
    }) as unknown as typeof fetch
    const sleep = jest.fn().mockResolvedValue(undefined)

    expect(await waitForAgentos({ fetchFn, sleep, intervalMs: 1 })).toBe(true)
    expect(calls).toBe(3)
    expect(sleep).toHaveBeenCalledTimes(2)
  })

  it('gives up after the timeout so the page can show its own error', async () => {
    const fetchFn = answer({ steps: { agentos: { state: 'running' } } })
    expect(await waitForAgentos({ fetchFn, timeoutMs: 20, intervalMs: 5 })).toBe(false)
  })
})
