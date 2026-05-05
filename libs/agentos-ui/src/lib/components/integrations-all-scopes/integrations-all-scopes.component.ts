import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, UserIntegrationConfig } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { map } from 'rxjs'
import {
  IntegrationConfigStateService,
  IntegrationConfigViewModel,
  IntegrationScope,
} from '../../services/integration-config-state.service'
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

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly listItems$ = this.state.vm$.pipe(map((vm) => this.toListItems(vm)))

  /**
   * Index resolving a list-item id → (config, scope) so the item template can render the
   * right component variant on the right slot. Maintained alongside `listItems$`.
   */
  private resolved = new Map<string, ResolvedItem>()

  ngOnInit(): void {
    this.state.setNamespace(this.namespaceId)
    this.state.vm$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((vm) => {
      const next = new Map<string, ResolvedItem>()
      vm.namespace.forEach((c) => next.set(c.id ?? '', { config: c, scope: 'namespace' }))
      vm.userOnNs.forEach((c) => next.set(c.id ?? '', { config: c, scope: 'userOnNs' }))
      vm.userGlobal.forEach((c) => next.set(c.id ?? '', { config: c, scope: 'userGlobal' }))
      this.resolved = next
    })
  }

  protected isEmptyPlaceholder(id: string): boolean {
    return id.startsWith(EMPTY_PREFIX)
  }

  protected resolve(id: string): ResolvedItem | null {
    return this.resolved.get(id) ?? null
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    // Default scope at create is 'namespace' — preserves the historical UX. The form's radio
    // selector lets the user switch to userOnNs / userGlobal before submit.
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: 'namespace' },
    })
  }

  protected onEdit(item: ResolvedItem): void {
    const id = item.config.id ?? ''
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', id, 'edit'], {
      queryParams: { scope: item.scope },
    })
  }

  protected onDelete(item: ResolvedItem): void {
    const id = item.config.id ?? ''
    this.state.delete(id, item.scope).pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
  }

  protected onOverride(config: IntegrationConfig): void {
    // Cross-link: navigate the user to the form pre-seeded with the NS config payload.
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'], {
      queryParams: { scope: 'userOnNs', template: config.id },
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
    return configs.map((c) => ({
      id: c.id ?? '',
      name: c.name,
      description: c.integrationType,
      groupKey: scope,
      groupLabel: SECTION_LABEL[scope],
    }))
  }
}
