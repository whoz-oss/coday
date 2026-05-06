import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, UserIntegrationConfig } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import {
  IntegrationConfigStateService,
  IntegrationConfigViewModel,
  IntegrationScope,
} from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { defaultCreateScope } from '../_shared/default-create-scope'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

const SECTION_LABEL: Readonly<Record<IntegrationScope, string>> = Object.freeze({
  namespace: 'Configurations du namespace',
  userOnNs: 'Mes overrides sur ce namespace',
  userGlobal: 'Mes overrides globaux',
})

const EMPTY_PREFIX = '__empty__'

type AnyConfig = IntegrationConfig | UserIntegrationConfig

interface ResolvedItem {
  config: AnyConfig
  scope: IntegrationScope
}

/**
 * IntegrationsAllScopesComponent — unified 3-section view for the Integrations page (story 6.5).
 *
 * Replaces `NamespaceIntegrationsComponent`. Renders three sections grouped via `ds-entity-list`:
 *   1. NS-shared configs        (groupKey: 'namespace')
 *   2. user × current namespace (groupKey: 'userOnNs')
 *   3. user-global              (groupKey: 'userGlobal')
 *
 * Empty sections show a discreet placeholder row (synthetic `__empty__*` items in the list,
 * detected in the item template) — see decision in story Dev Notes.
 *
 * All API calls are routed through `IntegrationConfigStateService`. The component never
 * touches `*ControllerService` directly, per `apps/client/docs/entity-crud-pattern.md`.
 */
@Component({
  selector: 'agentos-integrations-all-scopes',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, IntegrationConfigItemComponent, IconButtonComponent],
  templateUrl: './integrations-all-scopes.component.html',
  styleUrl: './integrations-all-scopes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(IntegrationConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string

  /**
   * Whether the current user can administrate this namespace's configs (super-admin OR
   * namespace ADMIN by relation). Drives [readOnly] on the namespace section.
   */
  protected readonly isAdmin = signal(false)

  protected readonly listItems$ = this.state.vm$.pipe(map((vm) => this.toListItems(vm)))

  /**
   * Index resolving a composite key (`<scope>:<id>`) → (config, scope) so the item template
   * can render the right component variant. The composite key prevents cross-scope collisions
   * when the same id exists in two scopes (theoretically possible across NS-shared and
   * user-overlay tables — UUIDs make it improbable, but the contract doesn't guarantee it).
   */
  private resolved = new Map<string, ResolvedItem>()

  ngOnInit(): void {
    // Reactive on namespaceId — protects against route param changes if RouteReuseStrategy
    // is later customized to keep the component mounted across navigations.
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const ns = params.get('namespaceId') ?? ''
      if (ns && ns !== this.namespaceId) this.namespaceId = ns
      this.state.setNamespace(ns)
    })

    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((ns) => (ns ? this.namespaceRole.isAdminOfNamespace$(ns) : of(false))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((v) => this.isAdmin.set(v))

    this.state.vm$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((vm) => {
      const next = new Map<string, ResolvedItem>()
      vm.namespace.forEach((c) => {
        if (c.id) next.set(this.itemKey('namespace', c.id), { config: c, scope: 'namespace' })
      })
      vm.userOnNs.forEach((c) => {
        if (c.id) next.set(this.itemKey('userOnNs', c.id), { config: c, scope: 'userOnNs' })
      })
      vm.userGlobal.forEach((c) => {
        if (c.id) next.set(this.itemKey('userGlobal', c.id), { config: c, scope: 'userGlobal' })
      })
      this.resolved = next
    })
  }

  protected isEmptyPlaceholder(id: string): boolean {
    return id.startsWith(EMPTY_PREFIX)
  }

  protected resolve(id: string): ResolvedItem | null {
    return this.resolved.get(id) ?? null
  }

  private itemKey(scope: IntegrationScope, id: string): string {
    return `${scope}:${id}`
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }

  protected onEdit(item: ResolvedItem): void {
    const id = item.config.id ?? ''
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', id, 'edit'], {
      queryParams: { scope: item.scope },
    })
  }

  protected onDelete(item: ResolvedItem): void {
    const id = item.config.id
    if (!id) return
    this.state
      .delete(id, item.scope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => {
          console.error(`[IntegrationsAllScopes] Delete failed for ${item.scope}:${id}:`, err)
        },
      })
  }

  protected onDuplicate(item: ResolvedItem): void {
    // Default destination scope = source scope (clone strict). The form's radio lets the
    // user pick any other destination. `templateScope` tells the form which controller to
    // use to load the source config for hydration.
    //
    // Smart-redirect for non-admins: cloning a NS item into NS scope would 403 at submit
    // for a non-admin (the form's radio also disables that option). Steer directly to
    // `userOnNs` so the user lands on a usable scope without manual re-pick.
    if (!item.config.id) return
    const destinationScope = item.scope === 'namespace' && !this.isAdmin() ? 'userOnNs' : item.scope
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: destinationScope, template: item.config.id, templateScope: item.scope },
    })
  }

  private toListItems(vm: IntegrationConfigViewModel): EntityListItem[] {
    const items: EntityListItem[] = []
    items.push(...this.sectionItems('namespace', vm.namespace))
    items.push(...this.sectionItems('userOnNs', vm.userOnNs))
    items.push(...this.sectionItems('userGlobal', vm.userGlobal))
    return items
  }

  private sectionItems(scope: IntegrationScope, configs: AnyConfig[]): EntityListItem[] {
    if (configs.length === 0) {
      return [
        {
          id: `${EMPTY_PREFIX}${scope}`,
          name: 'Aucune configuration',
          groupKey: scope,
          groupLabel: SECTION_LABEL[scope],
        },
      ]
    }
    return configs
      .filter((c): c is AnyConfig & { id: string } => !!c.id)
      .map((c) => ({
        id: this.itemKey(scope, c.id),
        name: c.name,
        description: c.integrationType,
        groupKey: scope,
        groupLabel: SECTION_LABEL[scope],
      }))
  }
}
