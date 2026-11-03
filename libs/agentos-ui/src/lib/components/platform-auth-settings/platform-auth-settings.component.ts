import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AuthSettingDto } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, SectionHeaderComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { AuthSettingConfigStateService } from '../../services/auth-setting-config-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { AuthSettingItemComponent } from '../auth-setting-item/auth-setting-item.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformAuthSettingsComponent — list view for platform-level auth settings.
 *
 * Loaded at /agentos/admin/auth-settings. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform auth settings have namespaceId IS NULL AND userId IS NULL.
 * Delegates all data access to AuthSettingConfigStateService (two-layer rule).
 */
@Component({
  selector: 'agentos-platform-auth-settings',
  imports: [EntityListComponent, SectionHeaderComponent, TableFilterComponent, AuthSettingItemComponent],
  templateUrl: './platform-auth-settings.component.html',
  styleUrl: './platform-auth-settings.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAuthSettingsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()

  /** Raw platform auth settings, kept for delete lookups. */
  private readonly settings$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return this.state.loadPlatformSettings()
    })
  )

  protected readonly settings = signal<AuthSettingDto[]>([])
  protected readonly rowFilter = createRowFilter(this.settings, (setting) =>
    [setting.name, setting.authType].filter(Boolean).join(' ')
  )
  protected readonly settingItems = computed<EntityListItem[]>(() =>
    this.rowFilter.filteredRows().map((setting) => ({
      id: setting.id ?? '',
      name: setting.name,
      description: setting.authType,
    }))
  )

  /** Full auth setting objects indexed by id — used to resolve itemTemplate events. */
  private settingsById = new Map<string, AuthSettingDto>()

  constructor() {
    this.settings$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (settings) => {
        this.settings.set(settings)
        this.settingsById = new Map(settings.map((s: AuthSettingDto) => [s.id ?? '', s]))
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'auth-settings', 'new'])
  }

  protected onEdit(setting: AuthSettingDto): void {
    if (!setting.id) return
    this.router.navigate(['/agentos', 'admin', 'auth-settings', setting.id, 'edit'])
  }

  protected onDelete(setting: AuthSettingDto): void {
    if (!setting.id) return
    this.deletion.execute(setting.id, {
      request$: this.state.delete(setting.id, 'platform').pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${setting.name}" failed. Try again.`,
    })
  }

  protected resolveSetting(id: string): AuthSettingDto | null {
    return this.settingsById.get(id) ?? null
  }
}
