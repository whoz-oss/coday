/**
 * The `status` Nx task, pure part: turns what the runtime knows (state.json, web-setup.json, health probes) into the
 * few lines worth printing in a small pane. No timers, no I/O: the caller polls and feeds `facts`.
 *
 * One line per MEANINGFUL change only: transitions (java, healthy, down...), the download percent at each decile,
 * and the URL to open. An unchanged situation prints nothing, however often it is polled.
 */

import { appUrl } from './app-url'
import { JavaStatus } from './java'
import { RuntimeState, ServiceKind } from './state'
import { WebSetup } from './web-setup'

export interface StatusFacts {
  /** state.json, undefined until provision wrote it. */
  state?: RuntimeState
  setup: WebSetup
  /**
   * Live health probes. For an external service the probe targets its own URL (what the proxy uses), never a
   * managed port.
   */
  healthy: Record<ServiceKind, boolean>
  /** `AGENTOS_URL` / `FACTORY_URL` when the service is not managed by this runtime. */
  external: Partial<Record<ServiceKind, string>>
  /** `CODAY_NO_FACTORY`: the managed Factory is left out, so there is nothing to report about it. */
  factoryDisabled?: boolean
}

const SERVICES: ServiceKind[] = ['agentos', 'factory']
/** A healthy service must fail this many consecutive probes before it is reported down (probes can time out). */
const DOWN_AFTER_MISSES = 3

/** The origin only: an external URL may carry credentials. */
function safeOrigin(url: string): string {
  try {
    return new URL(url).origin
  } catch {
    return 'configured'
  }
}

function javaLine(java: JavaStatus | undefined): { key: string; line: string } | undefined {
  if (!java) return undefined
  return java.state === 'ok'
    ? { key: 'ok', line: 'java ok' }
    : { key: java.state, line: `java ${java.state}: ${java.hint}` }
}

/** Returns a function to call on every poll: it gives back the lines to print (usually none). */
export function createStatusReporter(): (facts: StatusFacts) => string[] {
  const last = new Map<string, string>()
  const misses: Record<ServiceKind, number> = { agentos: 0, factory: 0 }
  const wasHealthy: Record<ServiceKind, boolean> = { agentos: false, factory: false }

  return ({ state, setup, healthy, external, factoryDisabled }) => {
    const lines: string[] = []
    const emit = (id: string, key: string, line: string): void => {
      if (last.get(id) === key) return
      last.set(id, key)
      lines.push(line)
    }

    if (!state?.provisioned) {
      emit('wait', 'waiting', 'waiting for provisioning...')
      return lines
    }

    const java = javaLine(state.java)
    if (java) emit('java', java.key, java.line)

    for (const name of SERVICES) {
      const external_ = external[name]
      if (external_) {
        emit(name, 'external', `${name} external ${safeOrigin(external_)}`)
        if (healthy[name]) wasHealthy[name] = true
        continue
      }
      if (name === 'factory' && factoryDisabled) continue
      if (healthy[name]) {
        misses[name] = 0
        wasHealthy[name] = true
        emit(name, 'healthy', `${name} healthy :${state.ports[name]}`)
        continue
      }
      misses[name]++
      if (wasHealthy[name]) {
        if (misses[name] >= DOWN_AFTER_MISSES) emit(name, 'down', `${name} not responding`)
        continue
      }

      const download = state.downloads[name]
      if (download?.state === 'error') {
        const reason = download.message ? `: ${download.message}` : ''
        emit(name, `error:${download.message ?? ''}`, `${name} download failed${reason}`)
      } else if (download?.state === 'running') {
        const percent = Math.round(download.percent ?? 0)
        emit(name, `download:${Math.floor(percent / 10)}`, `${name} download ${percent}%`)
      } else if (download?.state === 'done') {
        const next =
          name === 'agentos'
            ? state.profile === 'unmanaged' && !setup.identity
              ? 'waiting for first-run setup'
              : 'starting'
            : healthy.agentos || external.agentos
              ? 'starting'
              : 'waiting for agentos'
        emit(name, `ready:${next}`, `${name} jars ready, ${next}`)
      }
    }

    // Same URL as the browser open (open-browser.ts): the welcome page needs no AgentOS.
    const url = appUrl(state)
    if (url) {
      const line = `Open -> ${url}`
      emit('open', line, line)
    }
    return lines
  }
}
