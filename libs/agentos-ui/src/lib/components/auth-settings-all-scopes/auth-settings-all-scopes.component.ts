import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AuthSetting } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import {
  AuthSettingConfigStateService,
  AuthSettingConfigViewModel,
  AuthSettingScope,
} from '../../services/auth-setting-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { defaultCreateScope } from '../_shared/default-create-scope'
import { AuthSettingItemComponent } from '../auth-setting-item/auth-setting-item.component'

const SECTION_LABEL: Readonly<Record<AuthSettingScope, string>> = Object.freeze({
  namespace: 'Auth Settings du namespace',
  userOnNs: 'Mes overrides sur ce namespace',
  userGlobal: 'Mes overrides globaux',
})

const EMPTY_PREFIX = '__empty__'

type AnyAuthSetting = AuthSetting

interface ResolvedItem {
  config: AnyAuthSetting
  scope: AuthSettingScope
}

/**
 * AuthSettingsAllScopesComponent — unified 3-section view for the Auth Settings page
 * (Issue #1095, Phase 7).
 *
 * Renders three sections grouped via `ds-entity-list`:
 *   1. NS-shared auth settings      (groupKey: 'namespace')
 *   2. user × current namespace     (groupKey: 'userOnNs')
 *   3. user-global                  (groupKey: 'userGlobal')
 *
 * Pattern locked in story 6.5 (see `IntegrationsAllScopesComponent`) and 6.6
 * (see `AiProvidersAllScopesComponent`):
 *   - vm$ multicast handled in the state service via shareReplay
 *   - resolved index keyed by composite `<scope>:<id>` to prevent cross-scope collisions
 *   - read-only is wired by default on the namespace section (default-safe)
 *   - namespaceId is reactive via paramMap, defensive vs RouteReuseStrategy customizations
 */
@Component({
  selector: 'agentos-auth-settings-all-scopes',
  imports: [AsyncPipe, EntityListComponent, AuthSettingItemComponent, IconButtonComponent],
  templateUrl: './auth-settings-all-scopes.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthSettingsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string

  /** Whether the user can administrate this namespace (super-admin OR namespace ADMIN). */
  protected readonly isAdmin = signal(false)

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

  private itemKey(scope: AuthSettingScope, id: string): string {
    return `${scope}:${id}`
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }

  protected onEdit(item: ResolvedItem): void {
    const id = item.config.id
    if (!id) return
    this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', id, 'edit'], {
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
          console.error(`[AuthSettingsAllScopes] Delete failed for ${item.scope}:${id}:`, err)
        },
      })
  }

  protected onDuplicate(item: ResolvedItem): void {
    // Default destination = source scope (clone strict). Radio in the form lets the user
    // re-target. Smart-redirect for non-admins: a non-admin cloning a NS item into NS scope
    // would 403 at submit. Steer directly to `userOnNs`.
    if (!item.config.id) return
    const destinationScope = item.scope === 'namespace' && !this.isAdmin() ? 'userOnNs' : item.scope
    this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: destinationScope, template: item.config.id, templateScope: item.scope },
    })
  }

  private toListItems(vm: AuthSettingConfigViewModel): EntityListItem[] {
    const items: EntityListItem[] = []
    items.push(...this.sectionItems('namespace', vm.namespace))
    items.push(...this.sectionItems('userOnNs', vm.userOnNs))
    items.push(...this.sectionItems('userGlobal', vm.userGlobal))
    return items
  }

  private sectionItems(scope: AuthSettingScope, configs: AnyAuthSetting[]): EntityListItem[] {
    if (configs.length === 0) {
      return [
        {
          id: `${EMPTY_PREFIX}${scope}`,
          name: 'Aucun Auth Setting',
          groupKey: scope,
          groupLabel: SECTION_LABEL[scope],
        },
      ]
    }
    return configs
      .filter((c): c is AnyAuthSetting & { id: string } => !!c.id)
      .map((c) => ({
        id: this.itemKey(scope, c.id),
        name: c.name,
        description: c.authType,
        groupKey: scope,
        groupLabel: SECTION_LABEL[scope],
      }))
  }
}
