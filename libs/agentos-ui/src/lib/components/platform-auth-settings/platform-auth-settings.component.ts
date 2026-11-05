import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AuthSettingDto } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { AuthSettingConfigStateService } from '../../services/auth-setting-config-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformAuthSettingsComponent — table view for platform-level auth settings.
 *
 * Loaded at /agentos/admin/auth-settings. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform auth settings have namespaceId IS NULL AND userId IS NULL.
 * Delegates all data access to AuthSettingConfigStateService (two-layer rule).
 * Credentials are never displayed here: only the name and the authentication type.
 */
@Component({
  selector: 'agentos-platform-auth-settings',
  imports: [ButtonDirective, SectionHeaderComponent, TableDirective, TableFilterComponent, RowDeleteActionComponent],
  templateUrl: './platform-auth-settings.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAuthSettingsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly settings = signal<AuthSettingDto[]>([])
  protected readonly rowFilter = createRowFilter(this.settings, (setting) =>
    [setting.name, setting.authType].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.state.loadPlatformSettings()
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (settings) => {
          this.settings.set(settings)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'auth-settings', 'new'])
  }

  protected onEdit(setting: AuthSettingDto): void {
    if (!setting.id) return
    void this.router.navigate(['/agentos', 'admin', 'auth-settings', setting.id, 'edit'])
  }

  protected onDelete(setting: AuthSettingDto): void {
    if (!setting.id) return
    this.deletion.execute(setting.id, {
      request$: this.state.delete(setting.id, 'platform').pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${setting.name}" failed. Try again.`,
    })
  }
}
