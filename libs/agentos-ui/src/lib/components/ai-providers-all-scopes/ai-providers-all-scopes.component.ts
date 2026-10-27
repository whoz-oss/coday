import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AiProvider } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import {
  AiProviderConfigStateService,
  AiProviderConfigViewModel,
  AiProviderScope,
} from '../../services/ai-provider-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { defaultCreateScope } from '../_shared/default-create-scope'

interface AiProviderRow {
  key: string
  config: AiProvider
  scope: AiProviderScope
  scopeLabel: 'Platform' | 'Namespace' | 'Personal'
  canMutate: boolean
}

@Component({
  selector: 'agentos-ai-providers-all-scopes',
  imports: [SectionHeaderComponent, TableDirective, TagComponent],
  templateUrl: './ai-providers-all-scopes.component.html',
  styleUrl: './ai-providers-all-scopes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiProvidersAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AiProviderConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)
  protected namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly isAdmin = signal(false)
  protected readonly isLoading = signal(true)
  protected readonly rows = signal<AiProviderRow[]>([])

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('namespaceId') ?? ''
      if (id && id !== this.namespaceId) this.namespaceId = id
      this.state.setNamespace(id)
    })
    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((id) => (id ? this.namespaceRole.isAdminOfNamespace$(id) : of(false))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((isAdmin) => this.isAdmin.set(isAdmin))
    this.state.vm$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (vm) => {
        this.rows.set(this.toRows(vm))
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }
  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }
  protected edit(row: AiProviderRow): void {
    if (!row.canMutate || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', row.config.id, 'edit'], {
      queryParams: { scope: row.scope },
    })
  }
  protected delete(row: AiProviderRow): void {
    if (!row.canMutate || !row.config.id || !confirm(`Delete AI provider “${row.config.name}”?`)) return
    this.state.delete(row.config.id, row.scope).pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
  }
  protected duplicate(row: AiProviderRow): void {
    if (!row.config.id) return
    const scope = row.scope === 'platform' || (row.scope === 'namespace' && !this.isAdmin()) ? 'userOnNs' : row.scope
    void this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', 'new'], {
      queryParams: { scope, template: row.config.id, templateScope: row.scope },
    })
  }
  private toRows(vm: AiProviderConfigViewModel): AiProviderRow[] {
    return [
      ...this.mapScope(vm.platform, 'platform', 'Platform', false),
      ...this.mapScope(vm.namespace, 'namespace', 'Namespace', this.isAdmin()),
      ...this.mapScope(vm.userOnNs, 'userOnNs', 'Personal', true),
      ...this.mapScope(vm.userGlobal, 'userGlobal', 'Personal', true),
    ]
  }
  private mapScope(
    configs: AiProvider[],
    scope: AiProviderScope,
    scopeLabel: AiProviderRow['scopeLabel'],
    canMutate: boolean
  ): AiProviderRow[] {
    return configs.map((config, index) => ({
      key: `${scope}:${config.id ?? index}`,
      config,
      scope,
      scopeLabel,
      canMutate,
    }))
  }
}
