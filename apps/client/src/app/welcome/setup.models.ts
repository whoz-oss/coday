/**
 * Shapes of the first-run setup API (`/api/setup/*`, served by the Express server, not by AgentOS).
 * Mirror of apps/server/src/lib/setup/progress.ts and browse.ts.
 */

export type StepState = 'pending' | 'running' | 'done' | 'error' | 'blocked'

export interface StepStatus {
  state: StepState
  message?: string
}

export interface SetupProviderInfo {
  id: ProviderId
  label: string
  description: string
  keyUrl: string
}

export type ProviderId = 'anthropic' | 'openai' | 'requesty'

export interface SetupStatus {
  /** `laptop`: the wizard applies. `managed`: pre-wizard behaviour, never redirect to /welcome. */
  mode: 'laptop' | 'managed'
  setupEnabled: boolean
  setupDone: boolean
  identity?: { email: string }
  steps: {
    download: { state: StepState; percent?: number; message?: string }
    java: { state: StepState; version?: string; hint?: string }
    agentos: StepStatus
    /** Only present when `FACTORY_URL` points to a Factory: Coday never downloads nor runs one. */
    factory?: StepStatus
    providers: StepStatus
    models: StepStatus
    namespace: StepStatus
    agent: StepStatus
  }
  apply: { status: 'idle' | 'running' | 'done' | 'error'; error?: string }
  namespaceId?: string
  defaults?: { sharedExchangePath: string }
  providers?: SetupProviderInfo[]
}

export interface ApplyProviderRequest {
  id: ProviderId
  apiKey: string
  rank: number
}

export interface ApplyRequest {
  providers: ApplyProviderRequest[]
  sharedExchangePath: string
}

export interface BrowseEntry {
  name: string
  path: string
  symlink?: boolean
}

export interface BrowseResult {
  path: string
  parent: string | null
  home: string
  entries: BrowseEntry[]
  hiddenCount: number
}
