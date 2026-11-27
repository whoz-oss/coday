import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { FactoryApiError, FactoryGitHubSettings } from '../../core/factory-api.service'
import { GitHubTokenStateService } from '../../core/github-token-state.service'
import { RunConcurrencyStateService } from '../../core/run-concurrency-state.service'
import { ShellState } from '../../core/shell-state'

/** Where the active GitHub token comes from, as shown to the administrator. */
const SOURCE_LABELS: Record<FactoryGitHubSettings['source'], string> = {
  admin: 'Admin token',
  environment: 'Environment',
  'gh-cli': 'gh CLI',
  none: 'None',
}

/** Pill of the connection status: label, icon and tone (a CSS modifier). */
const STATUS_PILLS: Record<FactoryGitHubSettings['status'], { label: string; icon: string }> = {
  connected: { label: 'Connected', icon: 'check_circle' },
  invalid: { label: 'Invalid token', icon: 'error' },
  unreachable: { label: 'GitHub unreachable', icon: 'warning' },
  unconfigured: { label: 'Not connected', icon: 'link_off' },
}

/**
 * Factory administration: the maximum number of workflow runs executed in parallel, and the GitHub token the
 * deterministic PR checks step uses.
 *
 * The run limit is global to the Factory instance and applies at the next drain tick, with no restart. Extra runs wait
 * in the queue; 1 restores serialized execution. The GitHub token is verified against GitHub when saved; a rejected
 * token is not stored. The token itself is never shown back, only a masked hint.
 */
@Component({
  selector: 'sf-admin-page',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './admin-page.component.html',
  styleUrl: './admin-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RunConcurrencyStateService, GitHubTokenStateService],
})
export class AdminPageComponent {
  private readonly state = inject(RunConcurrencyStateService)
  private readonly githubState = inject(GitHubTokenStateService)

  protected readonly settings = this.state.settings
  protected readonly loading = this.state.loading
  protected readonly saving = this.state.saving

  /** What the user typed; `undefined` while untouched, `null` when the field is empty or not numeric. */
  private readonly edited = signal<number | null | undefined>(undefined)

  /** The value shown in the input: the user's edit, otherwise the server value. */
  protected readonly draft = computed(() => {
    const edited = this.edited()
    return edited !== undefined ? edited : (this.settings()?.maxParallelRuns ?? null)
  })

  protected readonly savedMessage = signal<string | null>(null)
  protected readonly saveError = signal<string | null>(null)

  protected readonly loadError = computed(() =>
    this.state.error() && !this.settings()
      ? this.state.error()?.message?.trim() || 'Unable to load the run concurrency setting.'
      : null
  )

  /** Inline validation message of the edited value, or null when there is nothing to reject. */
  protected readonly validation = computed(() => {
    const current = this.settings()
    if (!current || this.edited() === undefined) return null
    const value = this.draft()
    if (value === null || !Number.isInteger(value)) return 'Enter a whole number.'
    if (value < current.min || value > current.max) return `Enter a value between ${current.min} and ${current.max}.`
    return null
  })

  protected readonly canSave = computed(() => {
    const current = this.settings()
    const value = this.draft()
    return !!current && !this.validation() && value !== null && value !== current.maxParallelRuns && !this.saving()
  })

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Admin' }])
    this.load()
    this.githubState.load()
  }

  // ───────────── GitHub token ─────────────

  protected readonly github = this.githubState.settings
  protected readonly githubLoading = this.githubState.loading
  protected readonly githubBusy = this.githubState.saving

  /** What the administrator typed; never sent anywhere but the save call, and cleared once it is stored. */
  protected readonly tokenInput = signal('')
  protected readonly tokenVisible = signal(false)

  /** Result of the last save / remove / test: a banner under the form. */
  protected readonly githubFeedback = signal<{ tone: 'success' | 'error' | 'warn'; text: string } | null>(null)

  protected readonly githubLoadError = computed(() =>
    this.githubState.error() && !this.github()
      ? this.githubState.error()?.message?.trim() || 'Unable to load the GitHub connection status.'
      : null
  )

  protected readonly githubPill = computed(() => STATUS_PILLS[this.github()?.status ?? 'unconfigured'])
  protected readonly githubSourceLabel = computed(() => SOURCE_LABELS[this.github()?.source ?? 'none'])
  protected readonly canSaveToken = computed(() => this.tokenInput().trim().length > 0 && !this.githubBusy())

  protected onTokenChange(value: string): void {
    this.githubFeedback.set(null)
    this.tokenInput.set(value ?? '')
  }

  protected toggleTokenVisibility(): void {
    this.tokenVisible.update((visible) => !visible)
  }

  /** Verifies the token live and stores it unless GitHub rejects it. */
  protected saveToken(): void {
    if (!this.canSaveToken()) return
    this.githubFeedback.set(null)
    this.githubState.save(this.tokenInput().trim()).subscribe({
      next: (result) => {
        if (result.status === 'invalid') {
          // Not stored: keep what was typed so it can be corrected.
          this.githubFeedback.set({
            tone: 'error',
            text: 'GitHub rejected this token — check its permissions and try again.',
          })
          return
        }
        this.tokenInput.set('')
        this.tokenVisible.set(false)
        this.githubFeedback.set(
          result.status === 'connected'
            ? {
                tone: 'success',
                text: `Connected to GitHub${result.login ? ` as @${result.login}` : ''} — deterministic PR checks are ready.`,
              }
            : {
                tone: 'warn',
                text: 'Token saved, but GitHub could not be reached to verify it. Test the connection again later.',
              }
        )
      },
      error: (error: FactoryApiError) => {
        const message = error.message?.trim() || 'Unable to save the token.'
        this.githubFeedback.set({ tone: 'error', text: `${message} (${error.code})` })
      },
    })
  }

  /** Removes the token set from this page; an environment or `gh` token, if any, takes over. */
  protected removeToken(): void {
    if (this.githubBusy()) return
    this.githubFeedback.set(null)
    this.githubState.clear().subscribe({
      next: (result) =>
        this.githubFeedback.set({
          tone: 'success',
          text:
            result.source === 'none'
              ? 'Admin token removed. No GitHub token is configured.'
              : `Admin token removed. Falling back to the ${this.sourceLabel(result.source).toLowerCase()} token.`,
        }),
      error: (error: FactoryApiError) => {
        const message = error.message?.trim() || 'Unable to remove the token.'
        this.githubFeedback.set({ tone: 'error', text: `${message} (${error.code})` })
      },
    })
  }

  /** Re-tests the active token against GitHub now. */
  protected testConnection(): void {
    if (this.githubBusy()) return
    this.githubFeedback.set(null)
    this.githubState.test().subscribe({
      error: (error: FactoryApiError) => {
        const message = error.message?.trim() || 'Unable to test the connection.'
        this.githubFeedback.set({ tone: 'error', text: `${message} (${error.code})` })
      },
    })
  }

  protected loadGitHub(): void {
    this.githubFeedback.set(null)
    this.githubState.load()
  }

  protected sourceLabel(source: FactoryGitHubSettings['source']): string {
    return SOURCE_LABELS[source]
  }

  protected load(): void {
    this.edited.set(undefined)
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.state.load()
  }

  protected onValueChange(value: number | string | null): void {
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.edited.set(value === null || value === '' ? null : Number(value))
  }

  protected save(): void {
    const value = this.draft()
    if (!this.canSave() || value === null) return
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.state.save(value).subscribe({
      next: () => {
        this.edited.set(undefined)
        this.savedMessage.set(`Saved: up to ${this.settings()?.maxParallelRuns ?? value} workflow runs in parallel.`)
      },
      error: (error: FactoryApiError) => {
        const message = error.message?.trim() || 'Unable to save the setting.'
        this.saveError.set(`${message} (${error.code})`)
      },
    })
  }

  protected formatUpdatedAt(value: string): string {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
  }
}
