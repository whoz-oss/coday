import { CanActivateFn } from '@angular/router'

/** How long a navigation waits for AgentOS before giving up and letting the page show its own errors. */
const READY_TIMEOUT_MS = 90_000
const POLL_INTERVAL_MS = 1_500

/** Minimal shape of `GET /api/setup/status` this guard needs (the full shape lives in apps/client). */
interface SetupStatusLike {
  steps?: { agentos?: { state?: string } }
}

/**
 * Ask the Coday server whether AgentOS answers. Resolves:
 *  - true  : AgentOS is up, OR the setup API does not exist / is unreadable (an older server, an external
 *            AgentOS, a deployment): nothing to wait for, never block navigation on a missing endpoint.
 *  - false : the endpoint answered and AgentOS is still starting.
 */
export async function isAgentosReady(fetchFn: typeof fetch = fetch): Promise<boolean> {
  try {
    const res = await fetchFn('/api/setup/status', { headers: { Accept: 'application/json' } })
    if (!res.ok) return true
    const status = (await res.json()) as SetupStatusLike
    const state = status.steps?.agentos?.state
    return state === undefined || state === 'done'
  } catch {
    return true
  }
}

/** Wait until AgentOS is ready, polling, up to `timeoutMs`. Always resolves (never traps the user). */
export async function waitForAgentos(
  options: {
    timeoutMs?: number
    intervalMs?: number
    fetchFn?: typeof fetch
    sleep?: (ms: number) => Promise<void>
  } = {}
): Promise<boolean> {
  const {
    timeoutMs = READY_TIMEOUT_MS,
    intervalMs = POLL_INTERVAL_MS,
    fetchFn = fetch,
    sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)),
  } = options
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await isAgentosReady(fetchFn)) return true
    await sleep(intervalMs)
  }
  return false
}

/**
 * agentosReadyGuard — protects child routes until AgentOS answers.
 *
 * Right after the first run (or any restart) the UI is served before AgentOS has finished booting, and every
 * AgentOS call would fail. The guard waits on the server's `/api/setup/status` (AgentOS health, probed
 * server-side) for up to 90 s, then lets the navigation through anyway so the page can show its own error.
 * It never blocks when the setup API is missing (external AgentOS, older server).
 */
export const agentosReadyGuard: CanActivateFn = async () => {
  await waitForAgentos()
  return true
}
