import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AiModel, UserAiModel } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { combineLatest, map, of, switchMap } from 'rxjs'
import {
  AiModelConfigStateService,
  AiModelConfigViewModel,
  AiModelScope,
} from '../../services/ai-model-config-state.service'
import {
  AiProviderConfigStateService,
  AiProviderConfigViewModel,
  AiProviderScope,
} from '../../services/ai-provider-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { AiModelItemComponent, ParentProviderRef } from '../ai-model-item/ai-model-item.component'

const SECTION_LABEL: Readonly<Record<AiModelScope, string>> = Object.freeze({
  namespace: 'AI Models du namespace',
  userOnNs: 'Mes overrides sur ce namespace',
  userGlobal: 'Mes overrides globaux',
})

const EMPTY_PREFIX = '__empty__'

type AnyModel = AiModel | UserAiModel

interface ResolvedItem {
  config: AnyModel
  scope: AiModelScope
  parentProvider: ParentProviderRef | null
}

/**
 * AiModelsAllScopesComponent — unified 3-section view for the AI Models page (story 6.6).
 *
 * Same pattern as `AiProvidersAllScopesComponent` plus parent provider resolution: each model
 * row displays the name + scope of its parent provider so the user can distinguish, e.g., a
 * model attached to `ANTHROPIC_PREMIUM (USER × NS)` from the same name as `ANTHROPIC_PREMIUM (NS)`.
 *
 * Orphan models (parent provider not found in the user's view) stay visible with a discreet
 * warning per AC6 / FR30 (dormant override). The state service initialises both NS and user
 * provider sources so the lookup map is comprehensive.
 */
@Component({
  selector: 'agentos-ai-models-all-scopes',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, AiModelItemComponent, IconButtonComponent],
  templateUrl: './ai-models-all-scopes.component.html',
  styleUrl: './ai-models-all-scopes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AiModelConfigStateService)
  private readonly providerState = inject(AiProviderConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string

  /** Whether the user can administrate this namespace (super-admin OR namespace ADMIN). */
  protected readonly isAdmin = signal(false)

  protected readonly listItems$ = combineLatest([this.state.vm$, this.providerState.vm$]).pipe(
    map(([modelVm, providerVm]) => this.toListItems(modelVm, this.indexProvidersById(providerVm)))
  )

  private resolved = new Map<string, ResolvedItem>()

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const ns = params.get('namespaceId') ?? ''
      if (ns && ns !== this.namespaceId) this.namespaceId = ns
      this.state.setNamespace(ns)
      this.providerState.setNamespace(ns)
    })

    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((ns) => (ns ? this.namespaceRole.isAdminOfNamespace$(ns) : of(false))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((v) => this.isAdmin.set(v))

    combineLatest([this.state.vm$, this.providerState.vm$])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(([modelVm, providerVm]) => {
        const providerIndex = this.indexProvidersById(providerVm)
        const next = new Map<string, ResolvedItem>()
        modelVm.namespace.forEach((c) => {
          if (c.id) {
            next.set(this.itemKey('namespace', c.id), {
              config: c,
              scope: 'namespace',
              parentProvider: providerIndex.get(c.aiProviderId) ?? null,
            })
          }
        })
        modelVm.userOnNs.forEach((c) => {
          if (c.id) {
            next.set(this.itemKey('userOnNs', c.id), {
              config: c,
              scope: 'userOnNs',
              parentProvider: providerIndex.get(c.aiProviderId) ?? null,
            })
          }
        })
        modelVm.userGlobal.forEach((c) => {
          if (c.id) {
            next.set(this.itemKey('userGlobal', c.id), {
              config: c,
              scope: 'userGlobal',
              parentProvider: providerIndex.get(c.aiProviderId) ?? null,
            })
          }
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

  private itemKey(scope: AiModelScope, id: string): string {
    return `${scope}:${id}`
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'ai-models', 'new'], {
      queryParams: { scope: 'namespace' },
    })
  }

  protected onEdit(item: ResolvedItem): void {
    const id = item.config.id
    if (!id) return
    this.router.navigate(['/agentos', this.namespaceId, 'ai-models', id, 'edit'], {
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
          console.error(`[AiModelsAllScopes] Delete failed for ${item.scope}:${id}:`, err)
        },
      })
  }

  protected onDuplicate(item: ResolvedItem): void {
    if (!item.config.id) return
    this.router.navigate(['/agentos', this.namespaceId, 'ai-models', 'new'], {
      queryParams: { scope: item.scope, template: item.config.id, templateScope: item.scope },
    })
  }

  /**
   * Build an `id → ParentProviderRef` lookup across the 3 provider sources. Used to label
   * each model row with its parent provider's name and scope. Orphans (parent missing) get
   * a discreet warning treatment in the item template.
   */
  private indexProvidersById(providerVm: AiProviderConfigViewModel): Map<string, ParentProviderRef> {
    const index = new Map<string, ParentProviderRef>()
    const add = (id: string | undefined, name: string, scope: AiProviderScope) => {
      if (id) index.set(id, { name, scope })
    }
    providerVm.namespace.forEach((p) => add(p.id, p.name, 'namespace'))
    providerVm.userOnNs.forEach((p) => add(p.id, p.name, 'userOnNs'))
    providerVm.userGlobal.forEach((p) => add(p.id, p.name, 'userGlobal'))
    return index
  }

  private toListItems(vm: AiModelConfigViewModel, providerIndex: Map<string, ParentProviderRef>): EntityListItem[] {
    const items: EntityListItem[] = []
    items.push(...this.sectionItems('namespace', vm.namespace, providerIndex))
    items.push(...this.sectionItems('userOnNs', vm.userOnNs, providerIndex))
    items.push(...this.sectionItems('userGlobal', vm.userGlobal, providerIndex))
    return items
  }

  private sectionItems(
    scope: AiModelScope,
    configs: AnyModel[],
    providerIndex: Map<string, ParentProviderRef>
  ): EntityListItem[] {
    if (configs.length === 0) {
      return [
        {
          id: `${EMPTY_PREFIX}${scope}`,
          name: 'Aucun AI Model',
          groupKey: scope,
          groupLabel: SECTION_LABEL[scope],
        },
      ]
    }
    return configs
      .filter((c): c is AnyModel & { id: string } => !!c.id)
      .map((c) => {
        const parent = providerIndex.get(c.aiProviderId)
        const description = parent ? `${parent.name} · ${c.apiModelName}` : `Provider introuvable · ${c.apiModelName}`
        return {
          id: this.itemKey(scope, c.id),
          name: c.alias ?? c.apiModelName,
          description,
          groupKey: scope,
          groupLabel: SECTION_LABEL[scope],
        }
      })
  }
}
