/**
 * The text of the `status` pane (Nx mode): a few lines summarising the runtime. Pure: `(state, health) => string`.
 *
 * It is derived from `state.json` (downloads, java, agentosStarted, ports) plus live health probes, because
 * state.json has no "healthy" flag. The caller prints the block again only when it changed, so a TUI pane and a
 * plain stream both show a readable history instead of a flood. NO SIDE EFFECTS AT IMPORT TIME.
 */

import { JAVA_MIN_VERSION } from './java'
import { DownloadEntry, RuntimeState } from './state'

export interface StatusHealth {
  agentos?: boolean
  factory?: boolean
}

/** AGENTOS_URL / FACTORY_URL: services run elsewhere, nothing of ours to report nor to probe. */
export interface StatusExternal {
  agentosUrl?: string
  factoryUrl?: string
}

const row = (name: string, text: string): string => `${name.padEnd(8)}${text}`

const downloadFailed = (entry: DownloadEntry): string => `DOWNLOAD FAILED: ${entry.message ?? 'unknown error'}`

function javaRow(state: RuntimeState): string {
  const java = state.java
  if (!java) return row('Java', 'checking...')
  if (java.state === 'ok') return row('Java', `ok (${java.major})`)
  const what = java.state === 'too-old' ? `too old (${java.major})` : 'MISSING'
  return row('Java', `${what}, Java ${JAVA_MIN_VERSION}+ is required: ${java.hint}`)
}

function agentosPhase(state: RuntimeState, healthy: boolean, externalUrl: string | undefined): string {
  if (externalUrl) return `external (${externalUrl})`
  if (healthy) return 'healthy'
  const download = state.downloads.agentos
  if (download?.state === 'error') return downloadFailed(download)
  if (download?.state === 'running') return `downloading ${download.percent ?? 0}%`
  if (download?.state === 'done') {
    if (state.java && state.java.state !== 'ok') return `waiting for Java ${JAVA_MIN_VERSION}+`
    if (state.agentosStarted) return 'starting'
    // state.json does not hold the identity: a laptop AgentOS that has not been spawned yet waits for the wizard.
    if (state.profile === 'laptop')
      return `waiting for first-run setup -> http://localhost:${state.ports.express ?? 3000}/welcome`
    return 'starting'
  }
  return 'preparing'
}

function factoryPhase(
  state: RuntimeState,
  healthy: boolean,
  agentosReady: boolean,
  externalUrl: string | undefined
): string {
  if (externalUrl) return `external (${externalUrl})`
  if (healthy) return 'healthy'
  const download = state.downloads.factory
  if (download?.state === 'error') return downloadFailed(download)
  if (download?.state === 'running') return `downloading ${download.percent ?? 0}%`
  return agentosReady ? 'starting' : 'waiting for AgentOS'
}

export function renderStatus(
  state: RuntimeState | undefined,
  health: StatusHealth,
  external: StatusExternal = {}
): string {
  if (!state?.provisioned) return row('Coday', 'waiting for provisioning...')

  const { ports } = state
  const services = [
    !external.agentosUrl && ports.agentos ? `AgentOS ${ports.agentos}` : undefined,
    !external.factoryUrl && ports.factory ? `Factory ${ports.factory}` : undefined,
  ].filter((part): part is string => !!part)
  const url = ports.express ? `http://localhost:${ports.express}` : 'port not chosen yet'
  const header = row('Coday', services.length ? `${url}   (${services.join(' · ')})` : url)

  const agentosHealthy = !!health.agentos
  return [
    header,
    javaRow(state),
    row('AgentOS', agentosPhase(state, agentosHealthy, external.agentosUrl)),
    row('Factory', factoryPhase(state, !!health.factory, agentosHealthy || !!external.agentosUrl, external.factoryUrl)),
  ].join('\n')
}
