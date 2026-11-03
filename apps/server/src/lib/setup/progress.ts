/**
 * The setup state machine: what each wizard step looks like, derived from facts (state.json,
 * web-setup.json, health probes) plus the in-memory record of the apply run.
 *
 * `pending` -> `running` -> `done`, or `error`; `blocked` when a prerequisite is missing (Java).
 * Everything here is pure: the I/O lives in routes.ts.
 */

import { JavaStatus } from '../runtime/java'
import { overallDownload, RuntimeState } from '../runtime/state'
import { WebSetup } from '../runtime/web-setup'

export type StepState = 'pending' | 'running' | 'done' | 'error' | 'blocked'

export interface StepStatus {
  state: StepState
  message?: string
}

export const APPLY_STEPS = ['providers', 'models', 'namespace', 'agent'] as const
export type ApplyStep = (typeof APPLY_STEPS)[number]

export type ApplyStatus = 'idle' | 'running' | 'done' | 'error'

export interface ApplySnapshot {
  status: ApplyStatus
  steps: Record<ApplyStep, StepStatus>
  error?: string
  /** Personal namespace id, once known: the wizard lands there. */
  namespaceId?: string
}

export const idleApply = (): ApplySnapshot => ({
  status: 'idle',
  steps: {
    providers: { state: 'pending' },
    models: { state: 'pending' },
    namespace: { state: 'pending' },
    agent: { state: 'pending' },
  },
})

/** In-memory record of the apply run, shared by the apply runner and the status endpoint. */
export class ApplyTracker {
  private snapshot: ApplySnapshot = idleApply()

  get(): ApplySnapshot {
    return JSON.parse(JSON.stringify(this.snapshot)) as ApplySnapshot
  }

  isRunning(): boolean {
    return this.snapshot.status === 'running'
  }

  start(): void {
    this.snapshot = { ...idleApply(), status: 'running' }
  }

  step(step: ApplyStep, state: StepState, message?: string): void {
    this.snapshot.steps[step] = { state, ...(message ? { message } : {}) }
  }

  setNamespaceId(id: string): void {
    this.snapshot.namespaceId = id
  }

  finish(): void {
    this.snapshot.status = 'done'
  }

  fail(error: string): void {
    this.snapshot.status = 'error'
    this.snapshot.error = error
    // The running step (if any) is the one that failed.
    for (const step of APPLY_STEPS) {
      if (this.snapshot.steps[step].state === 'running') this.snapshot.steps[step] = { state: 'error', message: error }
    }
  }
}

export interface SetupStatus {
  /** `laptop`: the wizard applies. `managed`: pre-wizard behaviour, the client must not redirect. */
  mode: 'laptop' | 'managed'
  setupEnabled: boolean
  setupDone: boolean
  identity?: { email: string }
  steps: {
    download: { state: StepState; percent?: number; message?: string }
    java: { state: StepState; version?: string; hint?: string }
    agentos: StepStatus
    factory: StepStatus
    providers: StepStatus
    models: StepStatus
    namespace: StepStatus
    agent: StepStatus
  }
  apply: { status: ApplyStatus; error?: string }
  namespaceId?: string
}

export type IdentityDecision = { ok: true } | { ok: false; status: 403 | 409; message: string }

/**
 * May the identity be (re)written? It becomes the AgentOS bootstrap admin at ITS first boot, so
 * once AgentOS was spawned with an email, another email would silently not be the admin.
 * `agentosStarted` only covers the current run (each launch resets it): `agentosHasDatabase` says
 * an earlier run already booted AgentOS, which keeps its admin in that database.
 */
export function decideIdentityChange(
  setupEnabled: boolean,
  setup: WebSetup,
  state: RuntimeState | undefined,
  email: string,
  agentosHasDatabase = false
): IdentityDecision {
  if (!setupEnabled) return { ok: false, status: 403, message: 'First-run setup is not available here.' }
  if (setup.setupDone) return { ok: false, status: 403, message: 'Setup is already done.' }
  const current = setup.identity?.email
  if (current && current === email) return { ok: true }
  if (current && (state?.agentosStarted || agentosHasDatabase)) {
    return {
      ok: false,
      status: 409,
      message: `AgentOS already started for ${current}; to use another email, stop Coday and delete web-setup.json and the agentos data folder.`,
    }
  }
  return { ok: true }
}

export interface StatusFacts {
  setupEnabled: boolean
  setup: WebSetup
  state?: RuntimeState
  agentosHealthy: boolean
  factoryHealthy: boolean
  apply: ApplySnapshot
  /** `AGENTOS_URL` / `FACTORY_URL`: the service is not run by Coday, so nothing to download nor to check Java for. */
  external?: { agentos?: boolean; factory?: boolean }
}

function javaStep(java: JavaStatus | undefined): SetupStatus['steps']['java'] {
  if (!java) return { state: 'pending' }
  if (java.state === 'ok') return { state: 'done', version: java.version }
  return { state: 'blocked', hint: java.hint, ...(java.state === 'too-old' ? { version: java.version } : {}) }
}

/**
 * The wizard only waits for AgentOS: a Factory that cannot be downloaded must not block the first run (its
 * failure is reported on the `factory` step instead). So the Factory only contributes to the progress while it
 * has not failed.
 */
function downloadStep(
  state: RuntimeState | undefined,
  external: StatusFacts['external'] = {}
): SetupStatus['steps']['download'] {
  if (!state) return { state: 'pending' }
  if (external.agentos && external.factory) return { state: 'done', percent: 100 }
  const { agentos, factory } = state.downloads
  const overall = overallDownload({
    agentos: external.agentos ? undefined : agentos,
    factory: external.factory || factory?.state === 'error' ? undefined : factory,
  })
  switch (overall.state) {
    case 'done':
      return { state: 'done', percent: 100 }
    case 'error':
      return { state: 'error', message: overall.message }
    case 'running':
      return { state: 'running', percent: overall.percent }
    default:
      return { state: 'pending' }
  }
}

export function buildSetupStatus(facts: StatusFacts): SetupStatus {
  const { setup, state, apply } = facts
  const external = facts.external ?? {}
  const setupDone = !!setup.setupDone
  // Java only matters for what Coday runs itself.
  const java: SetupStatus['steps']['java'] =
    external.agentos && external.factory ? { state: 'done' } : javaStep(state?.java)
  const download = downloadStep(state, external)

  let agentos: StepStatus
  if (facts.agentosHealthy) agentos = { state: 'done' }
  // An external AgentOS is not ours to download or start: we only wait for it to answer.
  else if (external.agentos)
    agentos = state ? { state: 'running', message: 'Waiting for AgentOS' } : { state: 'pending' }
  else if (download.state === 'error') agentos = { state: 'error', message: download.message }
  else if (java.state === 'blocked') agentos = { state: 'blocked', message: 'Waiting for Java' }
  else if (!setup.identity && state?.profile === 'laptop')
    agentos = { state: 'pending', message: 'Waiting for your email' }
  else if (!state) agentos = { state: 'pending' }
  else if (state.agentosStarted) agentos = { state: 'running', message: 'Starting AgentOS' }
  else agentos = { state: 'running', message: download.state === 'done' ? 'Starting AgentOS' : 'Preparing AgentOS' }

  const factoryDownload = state?.downloads.factory
  const factory: StepStatus = facts.factoryHealthy
    ? { state: 'done' }
    : factoryDownload?.state === 'error'
      ? { state: 'error', message: factoryDownload.message }
      : agentos.state === 'done'
        ? { state: 'running' }
        : { state: 'pending' }

  // After a completed setup nothing is replayed: a restart must not show stale pending steps.
  const applyDone: StepStatus = { state: 'done' }
  const steps = setupDone
    ? { providers: applyDone, models: applyDone, namespace: applyDone, agent: applyDone }
    : apply.steps

  return {
    mode: state?.profile ?? 'managed',
    setupEnabled: facts.setupEnabled,
    setupDone,
    ...(setup.identity ? { identity: setup.identity } : {}),
    steps: { download, java, agentos, factory, ...steps },
    apply: { status: setupDone ? 'done' : apply.status, ...(apply.error ? { error: apply.error } : {}) },
    ...(apply.namespaceId ? { namespaceId: apply.namespaceId } : {}),
  }
}
