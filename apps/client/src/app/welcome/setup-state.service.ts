import { HttpErrorResponse } from '@angular/common/http'
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core'
import { catchError, firstValueFrom, of, Subscription, timer } from 'rxjs'
import { exhaustMap } from 'rxjs/operators'
import { SetupApiService } from './setup-api.service'
import { ApplyProviderRequest, BrowseResult, ProviderId, SetupStatus, StepState } from './setup.models'

export type WizardStep = 'email' | 'providers' | 'folder' | 'progress'
export const WIZARD_STEPS: WizardStep[] = ['email', 'providers', 'folder', 'progress']

/** One line of the live checklist, uniform whatever the underlying step (templates need a single shape). */
export interface ChecklistItem {
  key: string
  label: string
  state: StepState
  percent?: number
  message?: string
}

export interface ProviderDraft {
  id: ProviderId
  enabled: boolean
  apiKey: string
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const POLL_INTERVAL_MS = 1_500
const MIN_KEY_LENGTH = 8

export const errorMessage = (err: unknown, fallback: string): string => {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { error?: string } | string | null
    if (body && typeof body === 'object' && body.error) return body.error
    if (err.status === 0) return 'Cannot reach the Coday server.'
  }
  return fallback
}

/**
 * Business logic of the first-run wizard: the step the user is on, the answers (email, provider keys and
 * ranking, folder), the live checklist polled from `/api/setup/status`, and the final apply.
 *
 * API keys live in memory only (never in localStorage, never in the URL) and are sent once to
 * `POST /api/setup/apply`.
 */
@Injectable({ providedIn: 'root' })
export class SetupStateService {
  private readonly api = inject(SetupApiService)
  private readonly destroyRef = inject(DestroyRef)

  // --- server facts -------------------------------------------------------------------------------
  readonly status = signal<SetupStatus | null>(null)
  readonly statusError = signal<string | null>(null)

  // --- wizard state -------------------------------------------------------------------------------
  readonly step = signal<WizardStep>('email')
  readonly email = signal('')
  readonly providers = signal<ProviderDraft[]>([
    { id: 'anthropic', enabled: false, apiKey: '' },
    { id: 'openai', enabled: false, apiKey: '' },
    { id: 'requesty', enabled: false, apiKey: '' },
  ])
  readonly folder = signal('')

  readonly busy = signal(false)
  readonly error = signal<string | null>(null)

  // --- derived ------------------------------------------------------------------------------------
  readonly emailValid = computed(() => EMAIL_PATTERN.test(this.email().trim()))
  readonly enabledProviders = computed(() => this.providers().filter((p) => p.enabled))
  readonly providersValid = computed(() => {
    const enabled = this.enabledProviders()
    return enabled.length > 0 && enabled.every((p) => p.apiKey.trim().length >= MIN_KEY_LENGTH)
  })
  readonly folderValid = computed(() => this.folder().startsWith('/') || /^[A-Za-z]:[\\/]/.test(this.folder()))
  readonly stepIndex = computed(() => WIZARD_STEPS.indexOf(this.step()))

  /** Everything the checklist needs, in display order. */
  readonly checklist = computed<ChecklistItem[]>(() => {
    const s = this.status()?.steps
    if (!s) return []
    const line = (
      key: string,
      label: string,
      step: { state: StepState; percent?: number; message?: string; version?: string }
    ): ChecklistItem => ({
      key,
      label,
      state: step.state,
      percent: step.percent,
      message: step.message ?? (key === 'java' ? step.version : undefined),
    })
    return [
      line('download', 'Downloading AgentOS and the Factory', s.download),
      line('java', 'Checking Java 25', s.java),
      line('agentos', 'Starting AgentOS', s.agentos),
      line('providers', 'Configuring your AI providers', s.providers),
      line('models', 'Registering the models', s.models),
      line('namespace', 'Creating your personal workspace', s.namespace),
      line('agent', 'Deploying the Coday agent', s.agent),
    ]
  })
  readonly javaBlocked = computed(() => this.status()?.steps.java.state === 'blocked')
  readonly ready = computed(() => {
    const status = this.status()
    return !!status && status.apply.status === 'done' && !!status.namespaceId
  })
  readonly failed = computed(() => this.status()?.apply.status === 'error')
  readonly namespaceId = computed(() => this.status()?.namespaceId ?? null)

  private poller: Subscription | null = null

  constructor() {
    this.destroyRef.onDestroy(() => this.stopPolling())
  }

  // --- status -------------------------------------------------------------------------------------

  async refreshStatus(): Promise<SetupStatus | null> {
    try {
      const status = await firstValueFrom(this.api.getStatus())
      this.status.set(status)
      this.statusError.set(null)
      return status
    } catch (err) {
      this.statusError.set(errorMessage(err, 'Cannot read the setup status.'))
      return null
    }
  }

  startPolling(): void {
    if (this.poller) return
    this.poller = timer(0, POLL_INTERVAL_MS)
      .pipe(exhaustMap(() => this.api.getStatus().pipe(catchError(() => of(null)))))
      .subscribe((status) => {
        if (status) {
          this.status.set(status)
          this.statusError.set(null)
        } else {
          this.statusError.set('Lost contact with the Coday server, retrying...')
        }
      })
  }

  stopPolling(): void {
    this.poller?.unsubscribe()
    this.poller = null
  }

  /** Called once on entering /welcome: resume where the previous session stopped. */
  async init(): Promise<void> {
    const status = await this.refreshStatus()
    if (!status) return
    if (status.defaults?.sharedExchangePath && !this.folder()) this.folder.set(status.defaults.sharedExchangePath)
    if (status.identity && !this.email()) this.email.set(status.identity.email)
    // Download and AgentOS startup are already running in the background: show them from the start.
    this.startPolling()
    if (status.apply.status === 'running' || status.apply.status === 'error' || status.apply.status === 'done') {
      this.step.set('progress')
    }
  }

  // --- navigation ---------------------------------------------------------------------------------

  back(): void {
    const i = this.stepIndex()
    if (i > 0 && this.step() !== 'progress') this.step.set(WIZARD_STEPS[i - 1] as WizardStep)
    this.error.set(null)
  }

  /** Step 1: store the identity. It unblocks the AgentOS task (bootstrap admin). */
  async submitEmail(): Promise<void> {
    if (!this.emailValid() || this.busy()) return
    this.busy.set(true)
    this.error.set(null)
    try {
      await firstValueFrom(this.api.saveIdentity(this.email().trim()))
      await this.refreshStatus()
      this.step.set('providers')
    } catch (err) {
      this.error.set(errorMessage(err, 'Could not save your email.'))
    } finally {
      this.busy.set(false)
    }
  }

  submitProviders(): void {
    if (!this.providersValid()) return
    this.error.set(null)
    this.step.set('folder')
  }

  // --- providers ----------------------------------------------------------------------------------

  toggleProvider(id: ProviderId): void {
    this.providers.update((list) => {
      const target = list.find((p) => p.id === id)
      if (!target) return list
      const next = list.map((p) => (p.id === id ? { ...p, enabled: !p.enabled } : p))
      // A provider switched on joins the ranking at the bottom, whatever its position in the cards.
      if (!target.enabled) {
        const moved = next.find((p) => p.id === id) as ProviderDraft
        return [...next.filter((p) => p.id !== id), moved]
      }
      return next
    })
  }

  setApiKey(id: ProviderId, apiKey: string): void {
    this.providers.update((list) => list.map((p) => (p.id === id ? { ...p, apiKey } : p)))
  }

  /** Rank 1 = preferred. Only enabled providers are ranked; `delta` is -1 (up) or +1 (down). */
  moveProvider(id: ProviderId, delta: -1 | 1): void {
    this.providers.update((list) => {
      const enabled = list.filter((p) => p.enabled)
      const from = enabled.findIndex((p) => p.id === id)
      const to = from + delta
      if (from < 0 || to < 0 || to >= enabled.length) return list
      const reordered = [...enabled]
      const [item] = reordered.splice(from, 1)
      reordered.splice(to, 0, item as ProviderDraft)
      return [...reordered, ...list.filter((p) => !p.enabled)]
    })
  }

  /** Drag and drop: move `id` to the position of `targetId` in the ranking. */
  moveProviderTo(id: ProviderId, targetId: ProviderId): void {
    if (id === targetId) return
    this.providers.update((list) => {
      const enabled = list.filter((p) => p.enabled)
      const from = enabled.findIndex((p) => p.id === id)
      const to = enabled.findIndex((p) => p.id === targetId)
      if (from < 0 || to < 0) return list
      const reordered = [...enabled]
      const [item] = reordered.splice(from, 1)
      reordered.splice(to, 0, item as ProviderDraft)
      return [...reordered, ...list.filter((p) => !p.enabled)]
    })
  }

  rankOf(id: ProviderId): number | null {
    const i = this.enabledProviders().findIndex((p) => p.id === id)
    return i < 0 ? null : i + 1
  }

  // --- folder -------------------------------------------------------------------------------------

  browse(path?: string, showHidden = false): Promise<BrowseResult> {
    return firstValueFrom(this.api.browse(path, showHidden))
  }

  mkdir(path: string, name: string): Promise<{ path: string }> {
    return firstValueFrom(this.api.mkdir(path, name))
  }

  selectFolder(path: string): void {
    this.folder.set(path)
  }

  // --- apply --------------------------------------------------------------------------------------

  /** Step 3 -> 4: send everything to the server, which configures AgentOS. */
  async submitFolderAndApply(): Promise<void> {
    if (!this.providersValid() || !this.folderValid() || this.busy()) return
    this.busy.set(true)
    this.error.set(null)
    try {
      await firstValueFrom(this.api.apply({ providers: this.applyProviders(), sharedExchangePath: this.folder() }))
      this.step.set('progress')
      this.startPolling()
    } catch (err) {
      this.error.set(errorMessage(err, 'Could not start the setup.'))
    } finally {
      this.busy.set(false)
    }
  }

  /** Retry after a failed apply (idempotent on the server side). */
  async retry(): Promise<void> {
    await this.submitFolderAndApply()
  }

  private applyProviders(): ApplyProviderRequest[] {
    return this.enabledProviders().map((p, index) => ({ id: p.id, apiKey: p.apiKey.trim(), rank: index + 1 }))
  }

  /** The URL of the personal namespace, on a new case of the Coday agent. */
  landingCommands(): { path: string; queryParams: Record<string, string> } | null {
    const ns = this.namespaceId()
    return ns ? { path: '/home', queryParams: { ns } } : null
  }
}
