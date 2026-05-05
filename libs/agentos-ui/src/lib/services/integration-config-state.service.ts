import { inject, Injectable } from '@angular/core'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  UserIntegrationConfig,
  UserIntegrationConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, shareReplay, switchMap, tap } from 'rxjs'

/**
 * Scope of an integration config row in the unified 3-section view.
 * - `namespace` : config shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type IntegrationScope = 'namespace' | 'userOnNs' | 'userGlobal'

export interface IntegrationConfigViewModel {
  namespace: IntegrationConfig[]
  userOnNs: UserIntegrationConfig[]
  userGlobal: UserIntegrationConfig[]
}

/**
 * Shape used by callers when creating/updating a config — the fields that the user fills,
 * regardless of scope. The state service is responsible for assembling the final payload
 * (adding namespaceId / userId / id where appropriate) before hitting the controller.
 *
 * `description` is `string | null` (no `undefined`) so that an explicit clear (user emptied
 * the field) reaches the backend as JSON null. With `undefined`, JSON.stringify omits the
 * key and the backend keeps the previous value — silent broken-clear.
 */
export interface IntegrationConfigDraft {
  name: string
  integrationType: string
  description: string | null
  parameters?: unknown
}

/**
 * Sentinel string accepted by the backend's `?namespaceId=` query param to filter on
 * `namespaceId IS NULL` (user-global only). Defined by `UserIntegrationConfigController`
 * (Kotlin) — kept private here so no other component sees the literal.
 */
const NAMESPACE_NONE_SENTINEL = 'none'

/**
 * Page size used for the unified Integrations view. The backend exposes pagination but
 * the 3-section UI doesn't yet implement an infinite-scroll / pager — pulling a large
 * page is the pragmatic stop-gap until pagination is added (post-MVP).
 */
const LIST_PAGE_SIZE = 1000

/**
 * IntegrationConfigStateService — orchestrates the 3 sources of truth for the unified
 * Integrations page (story 6.5):
 *   1. NS-shared configs    → `IntegrationConfigControllerService.listByParent…`
 *   2. user × namespace     → `UserIntegrationConfigController.list(?namespaceId=<uuid>)`
 *   3. user-global          → `UserIntegrationConfigController.list(?namespaceId=none)`
 *
 * Components MUST inject this service rather than the generated controllers — that's the
 * contract from `apps/client/docs/entity-crud-pattern.md`. The service hides:
 *   - the `none` sentinel (callers speak `'global' | UUID` only — see `loadUserConfigs`)
 *   - the dispatch logic (callers pick a `scope`; the service routes to the right controller)
 *   - the refresh fan-out (a single `refresh()` re-fetches all 3 sources)
 */
@Injectable({ providedIn: 'root' })
export class IntegrationConfigStateService {
  private readonly nsController = inject(IntegrationConfigControllerService)
  private readonly userController = inject(UserIntegrationConfigControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  /**
   * Reactive view model for the all-scopes page. Re-emits whenever:
   *  - the active namespace changes (`setNamespace`)
   *  - any mutation calls `refresh()` (also done implicitly by create/update/delete)
   *
   * Multicast via `shareReplay` so concurrent subscribers (template async pipe + ngOnInit
   * derivations) share a single fan-out of HTTP calls instead of redoing all 3 GETs each.
   * Per-source `catchError` keeps the page rendering when one of the 3 layers fails — a
   * 5xx on user-global must not blank the namespace section.
   */
  readonly vm$: Observable<IntegrationConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      if (!namespaceId) {
        return combineLatest([
          this.loadNamespaceConfigs('').pipe(catchError(() => of([] as IntegrationConfig[]))),
          this.loadUserConfigs('global').pipe(catchError(() => of([] as UserIntegrationConfig[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as UserIntegrationConfig[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceConfigs(namespaceId).pipe(catchError(() => of([] as IntegrationConfig[]))),
        this.loadUserConfigs(namespaceId).pipe(catchError(() => of([] as UserIntegrationConfig[]))),
        this.loadUserConfigs('global').pipe(catchError(() => of([] as UserIntegrationConfig[]))),
      ]).pipe(map(([namespace, userOnNs, userGlobal]) => ({ namespace, userOnNs, userGlobal })))
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  )

  setNamespace(namespaceId: string): void {
    if (this.namespaceId$.value !== namespaceId) {
      this.namespaceId$.next(namespaceId)
    }
  }

  refresh(): void {
    this.refresh$.next()
  }

  /**
   * Wrapper around `listUserIntegrationConfig` that hides the `'none'` sentinel from callers.
   * Pass `'global'` for user-global rows, or a UUID for the user × namespace slice.
   */
  loadUserConfigs(scope: 'global' | string): Observable<UserIntegrationConfig[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.userController
      .listUserIntegrationConfig(namespaceParam, 0, LIST_PAGE_SIZE)
      .pipe(map((page) => page.content ?? []))
  }

  /**
   * Multicast, refresh-aware view of the caller's user-global configs (`namespaceId IS NULL`).
   * Tied to `refresh$` so a `create`/`update`/`delete` triggers re-emission for any subscriber
   * — consumed by `UserProfileComponent` so the recap stays in sync with mutations performed
   * from any namespace page. `catchError` per source so a 5xx doesn't blank the recap.
   */
  readonly userGlobal$: Observable<UserIntegrationConfig[]> = this.refresh$.pipe(
    switchMap(() => this.loadUserConfigs('global').pipe(catchError(() => of([] as UserIntegrationConfig[])))),
    shareReplay({ bufferSize: 1, refCount: true })
  )

  loadNamespaceConfigs(namespaceId: string): Observable<IntegrationConfig[]> {
    if (!namespaceId) return of([])
    return this.nsController.listByParentIntegrationConfig(namespaceId)
  }

  /**
   * Fetch a single config by id, dispatching on scope.
   * Used by the form for hydration from `?template=<id>` (cross-link override).
   */
  getById(id: string, scope: IntegrationScope): Observable<IntegrationConfig | UserIntegrationConfig> {
    return scope === 'namespace'
      ? this.nsController.getByIdIntegrationConfig(id)
      : this.userController.getByIdUserIntegrationConfig(id)
  }

  create(
    draft: IntegrationConfigDraft,
    scope: IntegrationScope,
    namespaceId: string | null
  ): Observable<IntegrationConfig | UserIntegrationConfig> {
    if (scope === 'namespace') {
      if (!namespaceId) throw new Error('Cannot create namespace-scoped config without a namespaceId')
      const payload: IntegrationConfig = {
        name: draft.name,
        integrationType: draft.integrationType,
        description: draft.description as string | undefined,
        namespaceId,
        parameters: draft.parameters,
      }
      return this.nsController.createIntegrationConfig(payload).pipe(tap(() => this.refresh()))
    }
    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs config without a namespaceId')
    }
    const userPayload: UserIntegrationConfig = {
      name: draft.name,
      integrationType: draft.integrationType,
      description: draft.description as string | undefined,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
      parameters: draft.parameters,
    }
    return this.userController.createUserIntegrationConfig(userPayload).pipe(tap(() => this.refresh()))
  }

  update(
    id: string,
    draft: IntegrationConfigDraft,
    scope: IntegrationScope,
    existing: IntegrationConfig | UserIntegrationConfig
  ): Observable<IntegrationConfig | UserIntegrationConfig> {
    // Build payloads explicitly — never spread `existing`. Spreading would re-inject `id`
    // (which lives in the path) and risks leaking stale fields like a previous-scope's
    // namespaceId on a hypothetical scope-change. Server-side immutable fields
    // (namespaceId, userId) are preserved by reading them from `existing`.
    if (scope === 'namespace') {
      const payload: IntegrationConfig = {
        name: draft.name,
        integrationType: draft.integrationType,
        description: draft.description as string | undefined,
        namespaceId: (existing as IntegrationConfig).namespaceId,
        parameters: draft.parameters,
      }
      return this.nsController.updateIntegrationConfig(id, payload).pipe(tap(() => this.refresh()))
    }
    const userExisting = existing as UserIntegrationConfig
    const userPayload: UserIntegrationConfig = {
      name: draft.name,
      integrationType: draft.integrationType,
      description: draft.description as string | undefined,
      userId: userExisting.userId,
      namespaceId: userExisting.namespaceId,
      parameters: draft.parameters,
    }
    return this.userController.updateUserIntegrationConfig(id, userPayload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: IntegrationScope): Observable<unknown> {
    const call$ =
      scope === 'namespace'
        ? this.nsController.deleteIntegrationConfig(id)
        : this.userController.deleteUserIntegrationConfig(id)
    return call$.pipe(tap(() => this.refresh()))
  }
}
