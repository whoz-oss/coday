import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AuthSettingDto } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import {
  AuthSettingConfigStateService,
  AuthSettingConfigViewModel,
  AuthSettingScope,
} from '../../services/auth-setting-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { defaultCreateScope } from '../_shared/default-create-scope'

interface AuthSettingRow {
  key: string
  config: AuthSettingDto
  scope: AuthSettingScope
  scopeLabel: 'Platform' | 'Namespace' | 'Personal'
  canMutate: boolean
}

@Component({
  selector: 'agentos-auth-settings-all-scopes',
  imports: [SectionHeaderComponent, TableDirective, TagComponent],
  templateUrl: './auth-settings-all-scopes.component.html',
  styleUrl: './auth-settings-all-scopes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthSettingsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly isAdmin = signal(false)
  protected readonly isLoading = signal(true)
  protected readonly rows = signal<AuthSettingRow[]>([])

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const namespaceId = params.get('namespaceId') ?? ''
      if (namespaceId && namespaceId !== this.namespaceId) this.namespaceId = namespaceId
      this.state.setNamespace(namespaceId)
    })
    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((id) => (id ? this.namespaceRole.isAdminOfNamespace$(id) : of(false))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((isAdmin) => this.isAdmin.set(isAdmin))
    this.state.vm$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (viewModel) => {
        this.rows.set(this.toRows(viewModel))
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }

  protected edit(row: AuthSettingRow): void {
    if (!row.canMutate || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', row.config.id, 'edit'], {
      queryParams: { scope: row.scope },
    })
  }

  protected delete(row: AuthSettingRow): void {
    if (!row.canMutate || !row.config.id || !confirm(`Delete auth setting “${row.config.name}”?`)) return
    this.state.delete(row.config.id, row.scope).pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
  }

  protected duplicate(row: AuthSettingRow): void {
    if (!row.config.id) return
    const destinationScope =
      row.scope === 'platform' || (row.scope === 'namespace' && !this.isAdmin()) ? 'userOnNs' : row.scope
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: destinationScope, template: row.config.id, templateScope: row.scope },
    })
  }

  private toRows(vm: AuthSettingConfigViewModel): AuthSettingRow[] {
    return [
      ...this.mapScope(vm.platform, 'platform', 'Platform', false),
      ...this.mapScope(vm.namespace, 'namespace', 'Namespace', this.isAdmin()),
      ...this.mapScope(vm.userOnNs, 'userOnNs', 'Personal', true),
      ...this.mapScope(vm.userGlobal, 'userGlobal', 'Personal', true),
    ]
  }

  private mapScope(
    configs: AuthSettingDto[],
    scope: AuthSettingScope,
    scopeLabel: AuthSettingRow['scopeLabel'],
    canMutate: boolean
  ): AuthSettingRow[] {
    return configs.map((config, index) => ({
      key: `${scope}:${config.id ?? index}`,
      config,
      scope,
      scopeLabel,
      canMutate,
    }))
  }
}
