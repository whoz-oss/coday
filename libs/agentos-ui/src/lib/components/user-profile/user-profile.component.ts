import { Location } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms'
import { Router } from '@angular/router'
import { combineLatest, map } from 'rxjs'
import { AiProviderConfigStateService } from '../../services/ai-provider-config-state.service'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { THEME_PORT, ThemeMode } from '../../services/theme.service'
import { EnterKeyBehavior, USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'

interface UserGlobalEntry {
  category: 'integration' | 'aiProvider'
  id: string
  name: string
  subtitle: string
}

interface UserGlobalRecap {
  integrations: UserGlobalEntry[]
  aiProviders: UserGlobalEntry[]
  total: number
}

/**
 * UserProfileComponent — full-page view and edit of the current user's profile.
 *
 * Two modes in one component, toggled by isEditing():
 *   - Read mode: displays all user fields (email, externalId read-only + firstname, lastname, bio)
 *   - Edit mode: reactive form for firstname, lastname, bio
 *
 * Story 6.6 also adds a "My global user configurations" section that recaps the user's
 * user-global overrides (`namespaceId IS NULL`) for Integrations and AI Providers.
 * The section is collapsable and discreet, and each entry exposes a
 * delete action — edit navigation requires a namespace context which `/me` doesn't have, so
 * users edit overrides from the namespace pages where they were created.
 */
@Component({
  selector: 'agentos-user-profile',
  imports: [ReactiveFormsModule],
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserProfileComponent implements OnInit {
  private readonly userState = inject(UserStateService)
  private readonly router = inject(Router)
  private readonly location = inject(Location)
  private readonly destroyRef = inject(DestroyRef)
  private readonly integrationState = inject(IntegrationConfigStateService)
  private readonly providerState = inject(AiProviderConfigStateService)
  private readonly themePort = inject(THEME_PORT)
  private readonly preferencesPort = inject(USER_PREFERENCES_PORT)
  protected readonly isEditing = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isSaving = signal(false)
  protected readonly isOverridesExpanded = signal(false)

  protected readonly currentUser = this.userState.currentUser

  /** Current theme mode (light / dark / system), reflected in the Appearance section. */
  protected readonly theme = this.themePort.theme
  protected readonly themeOptions: ReadonlyArray<{ value: ThemeMode; label: string }> = [
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
    { value: 'system', label: 'System' },
  ]

  /** Current ENTER-key behavior in the chat composer, reflected in the Composer section. */
  protected readonly enterKeyBehavior = this.preferencesPort.enterKeyBehavior
  protected readonly enterKeyOptions: ReadonlyArray<{ value: EnterKeyBehavior; label: string }> = [
    { value: 'send', label: 'Send' },
    { value: 'newline', label: 'New line' },
  ]

  protected readonly form = new FormGroup({
    firstname: new FormControl<string>('', { nonNullable: true }),
    lastname: new FormControl<string>('', { nonNullable: true }),
    bio: new FormControl<string>('', { nonNullable: true }),
  })

  /**
   * User-global recap — reads only the `namespaceId IS NULL` slice of each resource. The
   * combineLatest emits a single recap whenever any of the 3 sources updates (e.g. after a
   * delete from this view). The state services already cache via shareReplay.
   */
  protected readonly recap = toSignal(
    combineLatest([this.integrationState.userGlobal$, this.providerState.userGlobal$]).pipe(
      map(([integrations, providers]): UserGlobalRecap => {
        const integrationEntries = integrations.map(
          (c): UserGlobalEntry => ({
            category: 'integration',
            id: c.id ?? '',
            name: c.name,
            subtitle: c.integrationType,
          })
        )
        const providerEntries = providers.map(
          (p): UserGlobalEntry => ({
            category: 'aiProvider',
            id: p.id ?? '',
            name: p.name,
            subtitle: p.apiType,
          })
        )
        return {
          integrations: integrationEntries,
          aiProviders: providerEntries,
          total: integrationEntries.length + providerEntries.length,
        }
      })
    ),
    {
      initialValue: {
        integrations: [],
        aiProviders: [],
        total: 0,
      } as UserGlobalRecap,
    }
  )

  ngOnInit(): void {
    if (this.currentUser()) {
      this.syncFormFromState()
      return
    }
    this.isLoading.set(true)
    this.userState
      .loadMe()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.syncFormFromState()
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected startEditing(): void {
    this.syncFormFromState()
    this.isEditing.set(true)
  }

  protected cancelEditing(): void {
    this.isEditing.set(false)
  }

  protected setTheme(mode: ThemeMode): void {
    this.themePort.setTheme(mode)
  }

  protected setEnterKeyBehavior(behavior: EnterKeyBehavior): void {
    this.preferencesPort.setEnterKeyBehavior(behavior)
  }

  protected save(): void {
    if (this.isSaving()) return
    this.isSaving.set(true)
    this.userState
      .updateMe({
        firstname: this.form.controls.firstname.value.trim() || undefined,
        lastname: this.form.controls.lastname.value.trim() || undefined,
        bio: this.form.controls.bio.value.trim() || undefined,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSaving.set(false)
          this.isEditing.set(false)
        },
        error: () => this.isSaving.set(false),
      })
  }

  protected toggleOverrides(): void {
    this.isOverridesExpanded.update((v) => !v)
    this.pendingDeleteKey.set(null)
  }

  /**
   * Composite key of the entry that is currently in "armed" state (first click on
   * Delete). When non-null, the row template swaps the Delete button for
   * Confirm/Cancel. Mirrors the 2-step pattern used by the in-list items
   * ([io.whozoss.agentos.aiModel.AiModelItemComponent] etc.) so the UX is consistent
   * across the recap and the namespace-page lists.
   */
  protected readonly pendingDeleteKey = signal<string | null>(null)

  protected isPendingDelete(entry: UserGlobalEntry): boolean {
    return this.pendingDeleteKey() === this.entryKey(entry)
  }

  protected armDelete(entry: UserGlobalEntry): void {
    if (!entry.id) return
    this.pendingDeleteKey.set(this.entryKey(entry))
  }

  protected cancelDelete(): void {
    this.pendingDeleteKey.set(null)
  }

  protected confirmDelete(entry: UserGlobalEntry): void {
    if (!entry.id) return
    this.pendingDeleteKey.set(null)
    const call$ =
      entry.category === 'integration'
        ? this.integrationState.delete(entry.id, 'userGlobal')
        : this.providerState.delete(entry.id, 'userGlobal')

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      error: (err) => {
        console.error(`[UserProfile] Delete failed for ${entry.category}:${entry.id}:`, err)
      },
    })
  }

  private entryKey(entry: UserGlobalEntry): string {
    return `${entry.category}:${entry.id}`
  }

  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back()
    } else {
      this.router.navigate(['/agentos'])
    }
  }

  protected trackEntry(_index: number, entry: UserGlobalEntry): string {
    return this.entryKey(entry)
  }

  private syncFormFromState(): void {
    const user = this.currentUser()
    if (!user) return
    this.form.setValue({
      firstname: user.firstname ?? '',
      lastname: user.lastname ?? '',
      bio: user.bio ?? '',
    })
  }
}
