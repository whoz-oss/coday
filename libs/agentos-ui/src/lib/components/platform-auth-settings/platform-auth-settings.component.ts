import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AuthSettingControllerService, AuthSettingDto } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { AuthSettingItemComponent } from '../auth-setting-item/auth-setting-item.component'

/**
 * PlatformAuthSettingsComponent — list view for platform-level auth settings.
 *
 * Loaded at /agentos/admin/auth-settings. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform auth settings have namespaceId IS NULL (sentinel: 'none').
 */
@Component({
  selector: 'agentos-platform-auth-settings',
  imports: [AsyncPipe, EntityListComponent, AuthSettingItemComponent],
  templateUrl: './platform-auth-settings.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAuthSettingsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly authSettingController = inject(AuthSettingControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform auth settings, kept for delete lookups. */
  private readonly settings$ = this.refresh$.pipe(
    switchMap(() => this.authSettingController.listAuthSetting('none').pipe(map((raw) => raw as AuthSettingDto[])))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly settingItems$ = this.settings$.pipe(
    map((settings) =>
      settings.map(
        (s: AuthSettingDto): EntityListItem => ({
          id: s.id ?? '',
          name: s.name,
          description: s.authType,
        })
      )
    )
  )

  /** Full auth setting objects indexed by id — used to resolve itemTemplate events. */
  private settingsById = new Map<string, AuthSettingDto>()

  constructor() {
    this.settings$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((settings) => {
      this.settingsById = new Map(settings.map((s: AuthSettingDto) => [s.id ?? '', s]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
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
    this.authSettingController
      .deleteAuthSetting(setting.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveSetting(id: string): AuthSettingDto | null {
    return this.settingsById.get(id) ?? null
  }
}
