import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import {
  IntegrationConfigStateService,
  IntegrationConfigViewModel,
  IntegrationScope,
} from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { defaultCreateScope } from '../_shared/default-create-scope'
import { createRowFilter } from '../../utils/filter-rows'
import { IntegrationConfigSource, sourceOf } from '../../utils/integration-config-source'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

interface IntegrationRow {
  readonly key: string
  readonly config: IntegrationConfig
  readonly scope: IntegrationScope
  readonly scopeLabel: 'Platform' | 'Namespace' | 'Personal'
  readonly source: IntegrationConfigSource
  readonly canEdit: boolean
  readonly canDelete: boolean
}

@Component({
  selector: 'agentos-integrations-all-scopes',
  imports: [
    ButtonDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './integrations-all-scopes.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(IntegrationConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)
  private readonly namespaceApi = inject(NamespaceControllerService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly namespaceName = signal('this namespace')
  protected readonly isAdmin = signal(false)
  protected readonly isLoading = signal(true)
  protected readonly rows = signal<IntegrationRow[]>([])
  protected readonly deletion = createRowDeletionState()
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.config.name, row.config.integrationType, row.scopeLabel, row.source].join(' ')
  )

  ngOnInit(): void {
    this.state.setNamespace(this.namespaceId)
    if (this.namespaceId) {
      this.namespaceApi
        .getByIdNamespace(this.namespaceId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (namespace) => this.namespaceName.set(namespace.name) })
    }

    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((namespaceId) => (namespaceId ? this.namespaceRole.isAdminOfNamespace$(namespaceId) : of(false))),
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
    void this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }

  protected view(row: IntegrationRow): void {
    if (!row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'integrations', row.config.id, 'view'])
  }

  protected edit(row: IntegrationRow): void {
    if (!row.canEdit || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'integrations', row.config.id, 'edit'], {
      queryParams: { scope: row.scope },
    })
  }

  protected duplicate(row: IntegrationRow): void {
    // FILESYSTEM values may contain deployment-only paths or credentials. Do not seed a
    // writable form until the backend exposes an explicitly safe duplication contract.
    if (row.source === 'FILESYSTEM') return
    if (!row.config.id) return
    const privileged = row.scope === 'platform' || row.scope === 'namespace'
    const destinationScope = privileged && !this.isAdmin() ? 'userOnNs' : row.scope
    void this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: destinationScope, template: row.config.id, templateScope: row.scope },
    })
  }

  protected confirmDelete(row: IntegrationRow): void {
    if (!row.canDelete || !row.config.id) return
    this.deletion.execute(row.key, {
      request$: this.state.delete(row.config.id, row.scope).pipe(takeUntilDestroyed(this.destroyRef)),
      errorMessage: `Deleting "${row.config.name}" failed. Try again.`,
    })
  }

  private toRows(viewModel: IntegrationConfigViewModel): IntegrationRow[] {
    return [
      ...this.mapScope(viewModel.platform, 'platform', 'Platform', true),
      ...this.mapScope(viewModel.namespace, 'namespace', 'Namespace', !this.isAdmin()),
      ...this.mapScope(viewModel.userOnNs, 'userOnNs', 'Personal', false),
      ...this.mapScope(viewModel.userGlobal, 'userGlobal', 'Personal', false),
    ]
  }

  private mapScope(
    configs: IntegrationConfig[],
    scope: IntegrationScope,
    scopeLabel: IntegrationRow['scopeLabel'],
    readOnly: boolean
  ): IntegrationRow[] {
    return configs.map((config, index) => {
      const source = sourceOf(config)
      const mutable = source === 'NEO4J' && !readOnly
      return {
        key: `${scope}:${config.id ?? index}`,
        config,
        scope,
        scopeLabel,
        source,
        canEdit: mutable,
        canDelete: mutable,
      }
    })
  }
}
