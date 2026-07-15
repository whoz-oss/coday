import { inject, Injectable } from '@angular/core'
import { IntegrationConfig, IntegrationConfigControllerService } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, shareReplay, switchMap, tap } from 'rxjs'
import { multicastRefreshable } from './rxjs-state.utils'
import { UserStateService } from './user-state.service'

/**
 * Scope of an integration config row in the unified 4-section view.
 * - `platform`  : config shared at the platform level (super-admin only, no namespaceId, no userId)
 * - `namespace` : config shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type IntegrationScope = 'platform' | 'namespace' | 'userOnNs' | 'userGlobal'

export interface IntegrationConfigViewModel {
  platform: IntegrationConfig[]
  namespace: IntegrationConfig[]
  userOnNs: IntegrationConfig[]
  userGlobal: IntegrationConfig[]
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
  authSettingName?: string | null
}

/** Backend sentinel: `?namespaceId=none` means `namespaceId IS NULL` (user-global rows). */
const NAMESPACE_NONE_SENTINEL = 'none'
/** Backend sentinel: `?userId=me` means "the authenticated user". */
const USER_ME_SENTINEL = 'me'

/**
 * IntegrationConfigStateService — orchestrates the 4 sources of truth for the unified
 * Integrations page. All calls land on
 * `IntegrationConfigControllerService.listIntegrationConfig(namespaceId, userId, …)` :
 *
 *   0. platform         → `listIntegrationConfig()` (no params — super-admin only)
 *   1. NS-shared        → `listIntegrationConfig(namespaceId=<uuid>)` (no `userId`)
 *   2. user × namespace → `listIntegrationConfig(namespaceId=<uuid>, userId='me')`
 *   3. user-global      → `listIntegrationConfig(namespaceId='none', userId='me')`
 *
 * The implicit-scope dispatch on `POST` (Decision 15) lives server-side ; on the FE the
 * payload's `(namespaceId, userId)` pair encodes the intent — the create method assembles
 * it explicitly per scope.
 */
@Injectable({ providedIn: 'root' })
export class IntegrationConfigStateService {
  private readonly nsController = inject(IntegrationConfigControllerService)
  private readonly userState = inject(UserStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  /**
   * Reactive view model for the all-scopes page. Re-emits whenever:
   *  - the active namespace changes (`setNamespace`)
   *  - any mutation calls `refresh()` (also done implicitly by create/update/delete)
   *
   * Multicast via `shareReplay` so concurrent subscribers (template async pipe + ngOnInit
   * derivations) share a single fan-out of HTTP calls instead of redoing all 4 GETs each.
   * Per-source `catchError` keeps the page rendering when one of the layers fails — a
   * 5xx on user-global must not blank the namespace section. The platform section returns
   * an empty array for non-admins (backend returns 403, caught here).
   */
  readonly vm$: Observable<IntegrationConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      const platform$ = this.loadPlatformConfigs().pipe(catchError(() => of([] as IntegrationConfig[])))
      const userGlobal$ = this.loadUserConfigs('global').pipe(catchError(() => of([] as IntegrationConfig[])))

      if (!namespaceId) {
        return combineLatest([platform$, userGlobal$]).pipe(
          map(([platform, userGlobal]) => ({
            platform,
            namespace: [] as IntegrationConfig[],
            userOnNs: [] as IntegrationConfig[],
            userGlobal,
          }))
        )
      }
      return combineLatest([
        platform$,
        this.loadNamespaceConfigs(namespaceId).pipe(catchError(() => of([] as IntegrationConfig[]))),
        this.loadUserConfigs(namespaceId).pipe(catchError(() => of([] as IntegrationConfig[]))),
        userGlobal$,
      ]).pipe(map(([platform, namespace, userOnNs, userGlobal]) => ({ platform, namespace, userOnNs, userGlobal })))
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
   * Platform-level slice — no namespaceId, no userId. Super-admin only on the backend;
   * a non-admin will receive a 403 which is caught by `vm$`'s per-source `catchError`
   * (empty array fallback), so the page still renders for regular users.
   */
  loadPlatformConfigs(): Observable<IntegrationConfig[]> {
    return this.nsController.listIntegrationConfig()
  }

  /**
   * Wrapper around the unified `listIntegrationConfig` for the user-overlay slices. Callers
   * speak `'global'` (user-global rows) or a UUID (user × ns). Sentinels stay private.
   */
  loadUserConfigs(scope: 'global' | string): Observable<IntegrationConfig[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.nsController.listIntegrationConfig(namespaceParam, USER_ME_SENTINEL)
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<IntegrationConfig[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserConfigs('global'),
    [] as IntegrationConfig[]
  )

  loadNamespaceConfigs(namespaceId: string): Observable<IntegrationConfig[]> {
    if (!namespaceId) return of([])
    return this.nsController.listIntegrationConfig(namespaceId)
  }

  /**
   * Fetch a single config by id. With the unified controller all scopes share the same
   * `GET /api/integration-configs/{id}` route. The legacy `getById(id, scope)` overload
   * is kept for back-compat ; it now ignores `scope`.
   */
  getById(id: string): Observable<IntegrationConfig>
  getById(id: string, scope: IntegrationScope): Observable<IntegrationConfig>
  getById(id: string, _scope?: IntegrationScope): Observable<IntegrationConfig> {
    return this.nsController.getByIdIntegrationConfig(id)
  }

  create(
    draft: IntegrationConfigDraft,
    scope: IntegrationScope,
    namespaceId: string | null
  ): Observable<IntegrationConfig> {
    const me = this.userState.currentUser()
    const myId = me?.id
    if ((scope === 'userOnNs' || scope === 'userGlobal') && !myId) {
      throw new Error(
        `Cannot create ${scope} config before UserStateService.loadMe() resolves — call loadMe() at app init`
      )
    }

    if (scope === 'platform') {
      // Platform scope: no namespaceId, no userId — super-admin only, backend enforces it.
      const payload: IntegrationConfig = {
        name: draft.name,
        integrationType: draft.integrationType,
        description: draft.description as string | undefined,
        parameters: draft.parameters,
        authSettingName: draft.authSettingName ?? undefined,
      }
      return this.nsController.createIntegrationConfig(payload).pipe(tap(() => this.refresh()))
    }

    if (scope === 'namespace') {
      if (!namespaceId) throw new Error('Cannot create namespace-scoped config without a namespaceId')
      const payload: IntegrationConfig = {
        name: draft.name,
        integrationType: draft.integrationType,
        description: draft.description as string | undefined,
        namespaceId,
        parameters: draft.parameters,
        authSettingName: draft.authSettingName ?? undefined,
      }
      return this.nsController.createIntegrationConfig(payload).pipe(tap(() => this.refresh()))
    }

    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs config without a namespaceId')
    }

    // Decision 15 (Phase 2): scope inferred server-side from the (namespaceId, userId) pair.
    // userId MUST equal the authenticated principal — sending another user is a 400.
    const payload: IntegrationConfig = {
      name: draft.name,
      integrationType: draft.integrationType,
      description: draft.description as string | undefined,
      userId: myId,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
      parameters: draft.parameters,
      authSettingName: draft.authSettingName ?? undefined,
    }
    return this.nsController.createIntegrationConfig(payload).pipe(tap(() => this.refresh()))
  }

  update(
    id: string,
    draft: IntegrationConfigDraft,
    scope: IntegrationScope,
    existing: IntegrationConfig
  ): Observable<IntegrationConfig> {
    // Build payloads explicitly — never spread `existing`. Server-side immutable fields
    // (namespaceId, userId, integrationType) are preserved by the backend regardless of
    // what the body sends, but echoing the persisted scope keeps the wire payload clean.
    const payload: IntegrationConfig = {
      name: draft.name,
      integrationType: draft.integrationType,
      description: draft.description as string | undefined,
      userId: existing.userId,
      namespaceId: existing.namespaceId,
      parameters: draft.parameters,
      authSettingName: draft.authSettingName ?? undefined,
    }
    void scope
    return this.nsController.updateIntegrationConfig(id, payload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: IntegrationScope): Observable<unknown> {
    void scope
    return this.nsController.deleteIntegrationConfig(id).pipe(tap(() => this.refresh()))
  }
}
