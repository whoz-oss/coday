import { HttpErrorResponse } from '@angular/common/http'
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core'
import {
  AiProvider,
  AiProviderControllerService,
  NamespaceControllerService,
  User,
  UserControllerService,
} from '@whoz-oss/agentos-api-client'
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

/**
 * A platform-level AI provider the user can set a key for (managed mode). The key is saved as a user-global provider of
 * the same name, which AgentOS overlays on the platform one. `existingId` is set when the user already has a user-global
 * provider of that name without a key: it is then updated instead of created.
 */
export interface UserKeyDraft {
  name: string
  apiType: AiProvider['apiType']
  apiKey: string
  existingId?: string
}

/**
 * What `/welcome` shows: the first-run `wizard`, or, once the setup is done, a plain `loading` page that only waits for
 * the services (no steps, nothing that reads as a setup) and then hands over to the app.
 */
export type WelcomeMode = 'wizard' | 'loading'

/**
 * `/welcome?preview=fresh|nofresh`: look at the first-run wizard (`fresh`) or at the loading page (`nofresh`) whatever the
 * install state, and never leave for the app by itself. Meant for working on those pages on an install that is already
 * set up. It changes what is SHOWN only: the server still refuses the setup mutations once the setup is done.
 *
 * In a preview nothing is sent: saving the email and applying the setup only move to the next step, so every screen can
 * be walked through. The progress shows the real status of the install.
 */
export type WelcomePreview = 'fresh' | 'nofresh'

export const parseWelcomePreview = (value: string | null | undefined): WelcomePreview | null =>
  value === 'fresh' || value === 'nofresh' ? value : null

/** Where `/welcome` hands over to the app. */
export interface LandingTarget {
  path: string
  queryParams: Record<string, string>
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const POLL_INTERVAL_MS = 1_500
const MIN_KEY_LENGTH = 8
/**
 * With a configured Factory (FACTORY_URL), it gets this long, once AgentOS is up, to report healthy before the loading
 * page leaves without it (a Factory that cannot be reached must not hold the user back).
 */
const FACTORY_GRACE_MS = 20_000

const checklistLine = (
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
  private readonly users = inject(UserControllerService)
  private readonly namespaces = inject(NamespaceControllerService)
  private readonly aiProviders = inject(AiProviderControllerService)
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

  /** Decided once, on entering /welcome: `setupDone` flipping at the end of a first run must not change the page. */
  readonly mode = signal<WelcomeMode>('wizard')
  /** Set by `?preview=`: forces the mode and keeps the page where it is. */
  readonly preview = signal<WelcomePreview | null>(null)
  private readonly factoryGraceElapsed = signal(false)
  private factoryGraceTimer: ReturnType<typeof setTimeout> | null = null

  readonly busy = signal(false)
  readonly error = signal<string | null>(null)

  // --- managed mode: the profile the platform providers require (email, own keys) ---------------------
  /** The email typed by the user, when their profile has none. */
  readonly profileEmail = signal('')
  /** The user has no email yet. Only ever set when the platform offers AI providers. */
  readonly needsEmail = signal(false)
  /**
   * All the platform providers, when the user has no global key for any of them yet. Emptied once the keys are saved
   * (the ones that fail to save stay in).
   */
  readonly userKeys = signal<UserKeyDraft[]>([])
  readonly userKeysChecked = signal(false)
  private userKeysCheck: Promise<void> | null = null
  /** The current user as read by the check, kept for the email update (which must send the other fields back). */
  private me: User | null = null

  // --- derived ------------------------------------------------------------------------------------
  readonly emailValid = computed(() => EMAIL_PATTERN.test(this.email().trim()))
  readonly enabledProviders = computed(() => this.providers().filter((p) => p.enabled))
  readonly providersValid = computed(() => {
    const enabled = this.enabledProviders()
    return enabled.length > 0 && enabled.every((p) => p.apiKey.trim().length >= MIN_KEY_LENGTH)
  })
  readonly needsUserKeys = computed(() => this.userKeys().length > 0)
  /** The loading page must ask for something before handing over. */
  readonly needsProfile = computed(() => this.needsEmail() || this.needsUserKeys())
  /** At least one key typed, and every typed key plausible: the ones left empty are simply not set. */
  readonly userKeysValid = computed(() => {
    const typed = this.userKeys().filter((k) => k.apiKey.trim().length > 0)
    return typed.length > 0 && typed.every((k) => k.apiKey.trim().length >= MIN_KEY_LENGTH)
  })
  readonly profileEmailValid = computed(() => EMAIL_PATTERN.test(this.profileEmail().trim()))
  /** Whatever is asked is filled in: a plausible email when needed, and the keys rule when keys are needed. */
  readonly profileValid = computed(
    () => (!this.needsEmail() || this.profileEmailValid()) && (!this.needsUserKeys() || this.userKeysValid())
  )
  readonly folderValid = computed(() => this.folder().startsWith('/') || /^[A-Za-z]:[\\/]/.test(this.folder()))
  readonly stepIndex = computed(() => WIZARD_STEPS.indexOf(this.step()))

  /** Everything the checklist needs, in display order. */
  readonly checklist = computed<ChecklistItem[]>(() => {
    const s = this.status()?.steps
    if (!s) return []
    return [
      checklistLine('download', 'Downloading AgentOS', s.download),
      checklistLine('java', 'Checking Java 25', s.java),
      checklistLine('agentos', 'Starting AgentOS', s.agentos),
      checklistLine('providers', 'Configuring your AI providers', s.providers),
      checklistLine('models', 'Registering the models', s.models),
      checklistLine('namespace', 'Creating your personal workspace', s.namespace),
      checklistLine('agent', 'Deploying the Coday agent', s.agent),
    ]
  })

  /** The loading page: only the services, none of the setup steps (providers, models, namespace, agent). */
  readonly loadingChecklist = computed<ChecklistItem[]>(() => {
    const s = this.status()?.steps
    if (!s) return []
    return [
      checklistLine('download', 'Downloading AgentOS', s.download),
      checklistLine('java', 'Checking Java 25', s.java),
      checklistLine('agentos', 'Starting AgentOS', s.agentos),
      // Only with an external Factory (FACTORY_URL): Coday does not run one otherwise.
      ...(s.factory ? [checklistLine('factory', 'Waiting for the Factory', s.factory)] : []),
    ]
  })

  /**
   * The loading page can hand over to the app: AgentOS is up and, when a Factory is configured, it is up, failed, or
   * out of grace.
   */
  readonly loadingReady = computed(() => {
    const s = this.status()?.steps
    if (!s || s.agentos.state !== 'done') return false
    // Managed mode: the user is not handed over before the check of their profile is done and settled.
    if (this.status()?.mode === 'managed' && (!this.userKeysChecked() || this.needsProfile())) return false
    if (!s.factory) return true
    return s.factory.state === 'done' || s.factory.state === 'error' || this.factoryGraceElapsed()
  })
  readonly javaBlocked = computed(() => this.status()?.steps.java.state === 'blocked')
  readonly ready = computed(() => {
    const status = this.status()
    return !!status && status.apply.status === 'done' && !!status.namespaceId
  })
  readonly failed = computed(() => this.status()?.apply.status === 'error')
  /** A setup was attempted: the email is AgentOS's administrator now, the wizard no longer goes back to it. */
  readonly emailLocked = computed(() => !this.preview() && (this.status()?.apply.status ?? 'idle') !== 'idle')
  readonly namespaceId = computed(() => this.status()?.namespaceId ?? null)

  private poller: Subscription | null = null

  constructor() {
    this.destroyRef.onDestroy(() => this.stopPolling())
  }

  private setStatus(status: SetupStatus): void {
    this.status.set(status)
    this.statusError.set(null)
    if (this.mode() === 'loading' && status.steps.agentos.state === 'done' && !this.factoryGraceTimer) {
      this.factoryGraceTimer = setTimeout(() => this.factoryGraceElapsed.set(true), FACTORY_GRACE_MS)
    }
    this.maybeCheckUserKeys()
  }

  // --- status -------------------------------------------------------------------------------------

  async refreshStatus(): Promise<SetupStatus | null> {
    try {
      const status = await firstValueFrom(this.api.getStatus())
      this.setStatus(status)
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
          this.setStatus(status)
        } else {
          this.statusError.set('Lost contact with the Coday server, retrying...')
        }
      })
  }

  stopPolling(): void {
    this.poller?.unsubscribe()
    this.poller = null
    if (this.factoryGraceTimer) clearTimeout(this.factoryGraceTimer)
    this.factoryGraceTimer = null
  }

  /** Called once on entering /welcome: resume where the previous session stopped. */
  async init(preview: WelcomePreview | null = null): Promise<void> {
    this.preview.set(preview)
    const status = await this.refreshStatus()
    if (!status) return
    if (status.defaults?.sharedExchangePath && !this.folder()) this.folder.set(status.defaults.sharedExchangePath)
    if (status.identity && !this.email()) this.email.set(status.identity.email)
    // Download and AgentOS startup are already running in the background: show them from the start.
    this.startPolling()
    const wizard = preview
      ? preview === 'fresh'
      : status.mode === 'unmanaged' && status.setupEnabled && !status.setupDone
    if (!wizard) {
      // No wizard to show (setup done, or a mode without one: --auth, external AgentOS, existing install): only the
      // loading page, which hands over to the app once AgentOS is up.
      this.mode.set('loading')
      this.step.set('progress')
      this.maybeCheckUserKeys()
      return
    }
    // A preview of the wizard always starts at the first step, whatever an earlier apply did.
    if (!preview && ['running', 'error', 'done'].includes(status.apply.status)) {
      this.step.set('progress')
    }
  }

  // --- navigation ---------------------------------------------------------------------------------

  back(): void {
    const i = this.stepIndex()
    // Once a setup was attempted, AgentOS has made the email its administrator: the email step is behind us for good.
    const locked = this.step() === 'providers' && this.emailLocked()
    if (i > 0 && this.step() !== 'progress' && !locked) this.step.set(WIZARD_STEPS[i - 1] as WizardStep)
    this.error.set(null)
  }

  /**
   * After a failed apply, go back to the providers or the folder step to fix an answer (a wrong API key, typically),
   * then run the setup again from there. Not while an apply runs or once it succeeded. The email is not offered: AgentOS
   * made it its administrator when it started, it cannot change any more.
   */
  editAfterFailure(step: 'providers' | 'folder'): void {
    if (!this.failed() || this.step() !== 'progress') return
    this.error.set(
      step === 'providers' && !this.providersValid()
        ? 'Enter your API keys again: they are not kept when the page is reloaded.'
        : null
    )
    this.step.set(step)
  }

  /** Step 1: store the identity. It unblocks the AgentOS task (bootstrap admin). */
  async submitEmail(): Promise<void> {
    if (!this.emailValid() || this.busy()) return
    this.error.set(null)
    if (this.preview()) {
      this.step.set('providers')
      return
    }
    this.busy.set(true)
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
    this.error.set(null)
    if (this.preview()) {
      this.step.set('progress')
      return
    }
    this.busy.set(true)
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

  /**
   * Retry after a failed apply (idempotent on the server side). The keys live in memory only: after a page reload
   * they are gone, so the user goes back to the providers step to enter them again.
   */
  async retry(): Promise<void> {
    if (!this.providersValid()) {
      this.step.set('providers')
      this.error.set('Enter your API keys again: they are not kept when the page is reloaded.')
      return
    }
    await this.submitFolderAndApply()
  }

  // --- profile: email and user keys (managed mode) ----------------------------------------------------

  /** Once AgentOS is up, on the loading page of a managed install (no wizard, so providers are the platform's). */
  private maybeCheckUserKeys(): void {
    const status = this.status()
    if (this.mode() !== 'loading' || this.preview() || status?.mode !== 'managed') return
    if (status.steps.agentos.state !== 'done') return
    void this.checkUserKeys()
  }

  /**
   * Idempotent: looks once at what the platform requires from the user. Never blocks on a failure.
   *
   * A platform that offers AI providers (the ones of the platform are the only way to run an agent) requires an email
   * on the profile, and at least one personal key for one of those providers. A platform with none requires nothing.
   */
  checkUserKeys(): Promise<void> {
    this.userKeysCheck ??= this.loadProfileRequirements()
    return this.userKeysCheck
  }

  private async loadProfileRequirements(): Promise<void> {
    try {
      const [platform, mine, me] = await Promise.all([
        firstValueFrom(this.aiProviders.listAiProvider('none')),
        firstValueFrom(this.aiProviders.listAiProvider('none', 'me')),
        firstValueFrom(this.users.getMeUser()),
      ])
      if (platform.length === 0) return
      this.me = me
      this.needsEmail.set(!me.email?.trim())

      // Names are compared case-insensitively, as AgentOS overlays the platform provider by name.
      const owned = new Map(mine.map((p) => [p.name.toLowerCase(), p]))
      const platformNames = new Set(platform.map((p) => p.name.toLowerCase()))
      // One key for one platform provider is enough: the user can add the others from the AI providers page.
      const hasKey = mine.some((p) => !!p.apiKey && platformNames.has(p.name.toLowerCase()))
      if (!hasKey) {
        this.userKeys.set(
          platform.map((p) => ({
            name: p.name,
            apiType: p.apiType,
            apiKey: '',
            existingId: owned.get(p.name.toLowerCase())?.id,
          }))
        )
      }
    } catch (err) {
      // The app stays usable: the email and the keys can still be set from the profile and AI providers pages.
      console.warn('Could not check the profile requirements of the platform', err)
    } finally {
      this.userKeysChecked.set(true)
    }
  }

  setProfileEmail(value: string): void {
    this.profileEmail.set(value)
  }

  setUserKey(name: string, apiKey: string): void {
    this.userKeys.update((list) => list.map((k) => (k.name === name ? { ...k, apiKey } : k)))
  }

  /**
   * Saves what was asked: the email on the profile first (nothing else is tried if it fails), then the typed keys as
   * user-global providers. The keys that fail stay in the list with a message.
   */
  async submitProfile(): Promise<void> {
    if (!this.profileValid() || this.busy()) return
    this.error.set(null)
    this.busy.set(true)
    try {
      if (this.needsEmail() && !(await this.saveProfileEmail())) return
      if (this.needsUserKeys()) await this.saveUserKeys()
    } finally {
      this.busy.set(false)
    }
  }

  /** Puts the email on the profile, sending the other fields back unchanged. False (with the error set) on failure. */
  private async saveProfileEmail(): Promise<boolean> {
    try {
      const me = await this.currentUser()
      await firstValueFrom(
        this.users.updateUser(me.id as string, { ...me, email: this.profileEmail().trim().toLowerCase() })
      )
      this.needsEmail.set(false)
      return true
    } catch (err) {
      console.warn('Could not save the email of the user', err)
      this.error.set(errorMessage(err, 'Could not save your email.'))
      return false
    }
  }

  private async saveUserKeys(): Promise<void> {
    try {
      const me = await this.currentUser()
      const failed: UserKeyDraft[] = []
      for (const draft of this.userKeys()) {
        const apiKey = draft.apiKey.trim()
        if (!apiKey) continue
        const provider: AiProvider = { name: draft.name, apiType: draft.apiType, apiKey, userId: me.id }
        try {
          await firstValueFrom(
            draft.existingId
              ? this.aiProviders.updateAiProvider(draft.existingId, provider)
              : this.aiProviders.createAiProvider(provider)
          )
        } catch (err) {
          console.warn(`Could not save the key of ${draft.name}`, err)
          failed.push(draft)
        }
      }
      this.userKeys.set(failed)
      if (failed.length > 0) {
        this.error.set(`Could not save the key of ${failed.map((k) => k.name).join(', ')}. Check it and try again.`)
      }
    } catch (err) {
      this.error.set(errorMessage(err, 'Could not save your keys.'))
    }
  }

  /** The user read by the check, fetched only when the check did not keep it. */
  private async currentUser(): Promise<User> {
    this.me ??= await firstValueFrom(this.users.getMeUser())
    if (!this.me.id) throw new Error('The current user has no id')
    return this.me
  }

  private applyProviders(): ApplyProviderRequest[] {
    return this.enabledProviders().map((p, index) => ({ id: p.id, apiKey: p.apiKey.trim(), rank: index + 1 }))
  }

  /**
   * Where the loading page hands over: always the personal namespace of the user. The id is known to a server that ran
   * the apply since it started; otherwise it is looked up in AgentOS (personal namespaces are keyed `user:<externalId>`).
   * Only when that fails does the app pick the user's first namespace by itself.
   */
  async loadingTarget(): Promise<LandingTarget> {
    const ns = this.namespaceId() ?? (await this.personalNamespaceId())
    return { path: '/home', queryParams: ns ? { ns } : {} }
  }

  private async personalNamespaceId(): Promise<string | null> {
    try {
      const { externalId } = await firstValueFrom(this.users.getMeUser())
      if (!externalId) return null
      const key = `user:${externalId}`
      const found = await firstValueFrom(this.namespaces.listByExternalIdsNamespace([key]))
      return found.find((namespace) => namespace.externalId === key)?.id ?? null
    } catch (err) {
      console.warn('Could not look up the personal namespace', err)
      return null
    }
  }

  /** The URL of the personal namespace, on a new case of the default (Coday) agent. */
  landingCommands(): LandingTarget | null {
    const ns = this.namespaceId()
    return ns ? { path: '/home', queryParams: { ns } } : null
  }
}
