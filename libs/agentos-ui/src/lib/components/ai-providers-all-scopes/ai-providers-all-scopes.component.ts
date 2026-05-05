import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AiProvider, UserAiProvider } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { map } from 'rxjs'
import {
  AiProviderConfigStateService,
  AiProviderConfigViewModel,
  AiProviderScope,
} from '../../services/ai-provider-config-state.service'
import { AiProviderItemComponent } from '../ai-provider-item/ai-provider-item.component'

const SECTION_LABEL: Readonly<Record<AiProviderScope, string>> = Object.freeze({
  namespace: 'AI Providers du namespace',
  userOnNs: 'Mes overrides sur ce namespace',
  userGlobal: 'Mes overrides globaux',
})

const EMPTY_PREFIX = '__empty__'

type AnyProvider = AiProvider | UserAiProvider

interface ResolvedItem {
  config: AnyProvider
  scope: AiProviderScope
}

/**
 * AiProvidersAllScopesComponent — unified 3-section view for the AI Providers page (story 6.6).
 *
 * Replaces `NamespaceAiProvidersComponent`. Renders three sections grouped via `ds-entity-list`:
 *   1. NS-shared providers      (groupKey: 'namespace')
 *   2. user × current namespace (groupKey: 'userOnNs')
 *   3. user-global              (groupKey: 'userGlobal')
 *
 * Pattern locked in story 6.5 (see `IntegrationsAllScopesComponent`):
 *   - vm$ multicast handled in the state service via shareReplay
 *   - resolved index keyed by composite `<scope>:<id>` to prevent cross-scope collisions
 *   - read-only is wired by default on the namespace section (default-safe)
 *   - namespaceId is reactive via paramMap, defensive vs RouteReuseStrategy customizations
 */
@Component({
  selector: 'agentos-ai-providers-all-scopes',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, AiProviderItemComponent, IconButtonComponent],
  templateUrl: './ai-providers-all-scopes.component.html',
  styleUrl: './ai-providers-all-scopes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiProvidersAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AiProviderConfigStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly listItems$ = this.state.vm$.pipe(map((vm) => this.toListItems(vm)))

  /**
   * Index resolving a composite key (`<scope>:<id>`) → (config, scope) so the item template
   * can render the right component variant. Composite key prevents cross-scope collisions
   * when the same id exists in two scopes.
   */
  private resolved = new Map<string, ResolvedItem>()

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const ns = params.get('namespaceId') ?? ''
      if (ns && ns !== this.namespaceId) this.namespaceId = ns
      this.state.setNamespace(ns)
    })
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

  private itemKey(scope: AiProviderScope, id: string): string {
    return `${scope}:${id}`
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', 'new'], {
      queryParams: { scope: 'namespace' },
    })
  }

  protected onEdit(item: ResolvedItem): void {
    const id = item.config.id
    if (!id) return
    this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', id, 'edit'], {
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
          console.error(`[AiProvidersAllScopes] Delete failed for ${item.scope}:${id}:`, err)
        },
      })
  }

  protected onOverride(config: AiProvider): void {
    // Cross-link: navigate the user to the form pre-seeded with the NS provider as template.
    this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', 'new'], {
      queryParams: { scope: 'userOnNs', template: config.id },
    })
  }

  private toListItems(vm: AiProviderConfigViewModel): EntityListItem[] {
    const items: EntityListItem[] = []
    items.push(...this.sectionItems('namespace', vm.namespace))
    items.push(...this.sectionItems('userOnNs', vm.userOnNs))
    items.push(...this.sectionItems('userGlobal', vm.userGlobal))
    return items
  }

  private sectionItems(scope: AiProviderScope, configs: AnyProvider[]): EntityListItem[] {
    if (configs.length === 0) {
      return [
        {
          id: `${EMPTY_PREFIX}${scope}`,
          name: 'Aucun AI Provider',
          groupKey: scope,
          groupLabel: SECTION_LABEL[scope],
        },
      ]
    }
    return configs
      .filter((c): c is AnyProvider & { id: string } => !!c.id)
      .map((c) => ({
        id: this.itemKey(scope, c.id),
        name: c.name,
        description: c.apiType,
        groupKey: scope,
        groupLabel: SECTION_LABEL[scope],
      }))
  }
}
