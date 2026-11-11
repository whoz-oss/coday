/**
 * `<configDir>/runtime/state.json`: what the `provision` step discovered and what the other
 * processes (agentos/factory/server tasks, the setup API) need to agree on: ports, Java status,
 * download progress. Written atomically, read at request time.
 *
 * Several processes write it (provision, the agentos task, the factory task): every writer goes
 * through `updateState`, a read-merge-write of ITS OWN keys, so they never clobber each other
 * beyond a tiny read/write window (each key has exactly one writer).
 */

import { JavaStatus } from './java'
import { readJsonFile, writeJsonFile } from './json-file'
import { statePath } from './paths'

export type RuntimeProfile = 'unmanaged' | 'managed'
export type ServiceKind = 'agentos' | 'factory'
export type DownloadState = 'idle' | 'running' | 'done' | 'error'

export interface DownloadEntry {
  state: DownloadState
  percent?: number
  message?: string
}

export interface RuntimeState {
  orchestrator: 'nx' | 'in-process'
  profile: RuntimeProfile
  ports: { express?: number; agentos?: number; agentosBolt?: number; factory?: number }
  /** Block base the stack ran on at its last start (see ports.ts). Information only: it never influences the choice. */
  portBlock?: number
  versions?: { agentos: string; factory: string }
  java?: JavaStatus
  /** Written by the task that owns the service (agentos task -> agentos, factory task -> factory). */
  downloads: Partial<Record<ServiceKind, DownloadEntry>>
  /** True once provision finished (ports chosen, secrets ready, Java inspected). */
  provisioned: boolean
  /** Set by the agentos task once it spawned the JVM: the bootstrap identity cannot change anymore. */
  agentosStarted?: boolean
  updatedAt: string
}

export const initialState = (orchestrator: RuntimeState['orchestrator'], profile: RuntimeProfile): RuntimeState => ({
  orchestrator,
  profile,
  ports: {},
  downloads: {},
  provisioned: false,
  updatedAt: new Date(0).toISOString(),
})

export function readState(configDir: string): RuntimeState | undefined {
  return readJsonFile<RuntimeState>(statePath(configDir))
}

export type StatePatch = Partial<Omit<RuntimeState, 'ports' | 'downloads' | 'updatedAt'>> & {
  ports?: RuntimeState['ports']
  downloads?: RuntimeState['downloads']
}

/** Merge `patch` into `current` (ports and downloads are merged one level deep). */
export function mergeState(current: RuntimeState, patch: StatePatch, now: () => Date = () => new Date()): RuntimeState {
  return {
    ...current,
    ...patch,
    ports: { ...current.ports, ...patch.ports },
    downloads: { ...current.downloads, ...patch.downloads },
    updatedAt: now().toISOString(),
  }
}

export function updateState(
  configDir: string,
  patch: StatePatch,
  fallback: RuntimeState = initialState('in-process', 'managed')
): RuntimeState {
  const next = mergeState(readState(configDir) ?? fallback, patch)
  writeJsonFile(statePath(configDir), next)
  return next
}

/** Combined download percentage (AgentOS is ~85% of the bytes) for a single progress bar. */
export function overallDownload(downloads: RuntimeState['downloads']): DownloadEntry {
  const entries: Array<[number, DownloadEntry | undefined]> = [
    [0.85, downloads.agentos],
    [0.15, downloads.factory],
  ]
  const known = entries.filter(([, e]) => !!e) as Array<[number, DownloadEntry]>
  if (known.length === 0) return { state: 'idle' }
  const failed = known.find(([, e]) => e.state === 'error')
  if (failed) return { state: 'error', message: failed[1].message }
  const weightSum = known.reduce((sum, [w]) => sum + w, 0)
  const percent = Math.round(
    known.reduce((sum, [w, e]) => sum + w * (e.state === 'done' ? 100 : (e.percent ?? 0)), 0) / weightSum
  )
  if (known.every(([, e]) => e.state === 'done')) return { state: 'done', percent: 100 }
  return { state: known.some(([, e]) => e.state === 'running') ? 'running' : 'idle', percent }
}
