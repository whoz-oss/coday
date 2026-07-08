import { inject, Injectable } from '@angular/core'
import { AuthSetting, AuthSettingControllerService } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, shareReplay, switchMap, tap } from 'rxjs'
import { multicastRefreshable } from './rxjs-state.utils'
import { UserStateService } from './user-state.service'

/**
 * Scope of an auth setting row in the unified 3-section view.
 * - `namespace` : setting shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type AuthSettingScope = 'namespace' | 'userOnNs' | 'userGlobal'

export interface AuthSettingConfigViewModel {
  namespace: AuthSetting[]
  userOnNs: AuthSetting[]
  userGlobal: AuthSetting[]
}

/**
 * Shape used by callers when creating/updating an auth setting — the fields the user fills,
 * regardless of scope. The state service assembles the final payload (adding namespaceId /
 * userId / id / preserving masked data values on update) before hitting the controller.
 *
 * `data` semantics:
 *   - non-null object with entries → caller wants to set these key-value pairs
 *   - empty object `{}`            → caller wants to clear all data keys
 *   - null                         → caller did NOT touch the data map (keep previous values);
 *                                    the state service omits data from the update payload (NFR-SEC-1)
 *
 * Per-key masking: individual data values may also be sentinel-masked by the backend.
 * The form tracks `initialData` and compares on submit; if a value is unchanged from the
 * server sentinel, the key is omitted from the update payload so the backend keeps the
 * persisted credential.
 */
export interface AuthSettingDraft {
  name: string
  authType: AuthSetting['authType']
  description: string | null
  /**
   * Key-value credential map.
   * `null`  → user did not touch the data section (omit from payload).
   * `{}`    → user cleared all entries.
   * Non-null non-empty → user set these entries.
   */
  data: { [key: string]: string } | null
}

/** Sentinel accepted by the backend's `?namespaceId=` to filter on `namespaceId IS NULL`. */
const NAMESPACE_NONE_SENTINEL = 'none'
/** Sentinel accepted by the backend's `?userId=` to mean "the authenticated user". */
const USER_ME_SENTINEL = 'me'

/**
 * AuthSettingConfigStateService — orchestrates the 3 sources of truth for the unified
 * Auth Settings page (Issue #1095, Phase 7). All 3 calls land on the single
 * `AuthSettingControllerService.listAuthSetting(namespaceId, userId)`, with the scope
 * distinguished by query params:
 *
 *   1. NS-shared        → `listAuthSetting(namespaceId=<uuid>)` (no `userId`)
 *   2. user × namespace → `listAuthSetting(namespaceId=<uuid>, userId='me')`
 *   3. user-global      → `listAuthSetting(namespaceId='none', userId='me')`
 *
 * The implicit-scope dispatch on `POST` (Decision 15) lives server-side; on the FE the
 * payload's `(namespaceId, userId)` pair encodes the intent — the create method assembles
 * it explicitly per scope.
 */
@Injectable({ providedIn: 'root' })
export class AuthSettingConfigStateService {
  private readonly controller = inject(AuthSettingControllerService)
  private readonly userState = inject(UserStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  /**
   * Reactive view model for the all-scopes page. Multicast via `shareReplay` so concurrent
   * subscribers (template async pipe + ngOnInit derivations) share a single fan-out of HTTP
   * calls instead of redoing all 3 GETs each. Per-source `catchError` keeps the page rendering
   * when one of the 3 layers fails — a 5xx on user-global must not blank the namespace section.
   */
  readonly vm$: Observable<AuthSettingConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      if (!namespaceId) {
        return combineLatest([
          this.loadNamespaceSettings('').pipe(catchError(() => of([] as AuthSetting[]))),
          this.loadUserSettings('global').pipe(catchError(() => of([] as AuthSetting[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as AuthSetting[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceSettings(namespaceId).pipe(catchError(() => of([] as AuthSetting[]))),
        this.loadUserSettings(namespaceId).pipe(catchError(() => of([] as AuthSetting[]))),
        this.loadUserSettings('global').pipe(catchError(() => of([] as AuthSetting[]))),
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
   * Wrapper around the unified `listAuthSetting` for the user-overlay slices. Callers speak
   * `'global'` (user-global rows: `namespaceId IS NULL`) or a concrete UUID (user × namespace
   * for that namespace). The `'none'` / `'me'` sentinels stay private.
   */
  loadUserSettings(scope: 'global' | string): Observable<AuthSetting[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.controller.listAuthSetting(namespaceParam, USER_ME_SENTINEL)
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<AuthSetting[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserSettings('global'),
    [] as AuthSetting[]
  )

  loadNamespaceSettings(namespaceId: string): Observable<AuthSetting[]> {
    if (!namespaceId) return of([])
    // NS-shared: `userId` omitted → backend serves the namespace-scoped layer for that NS.
    return this.controller.listAuthSetting(namespaceId)
  }

  /**
   * Fetch a single auth setting by id. The unified `GET /api/auth-settings/{id}` route
   * handles all scopes — the evaluator's ownership branch decides authz.
   */
  getById(id: string): Observable<AuthSetting>
  getById(id: string, scope: AuthSettingScope): Observable<AuthSetting>
  getById(id: string, _scope?: AuthSettingScope): Observable<AuthSetting> {
    return this.controller.getByIdAuthSetting(id)
  }

  create(draft: AuthSettingDraft, scope: AuthSettingScope, namespaceId: string | null): Observable<AuthSetting> {
    const me = this.userState.currentUser()
    const myId = me?.id
    if ((scope === 'userOnNs' || scope === 'userGlobal') && !myId) {
      throw new Error(
        `Cannot create ${scope} auth setting before UserStateService.loadMe() resolves — call loadMe() at app init`
      )
    }

    if (scope === 'namespace') {
      if (namespaceId === undefined || namespaceId === '') {
        throw new Error('Cannot create namespace-scoped auth setting without a namespaceId')
      }
      const payload: AuthSetting = {
        name: draft.name,
        authType: draft.authType,
        description: draft.description as string | undefined,
        data: draft.data ?? undefined,
        // null → platform scope (namespaceId IS NULL); string UUID → namespace scope
        namespaceId: namespaceId ?? undefined,
      }
      return this.controller.createAuthSetting(payload).pipe(tap(() => this.refresh()))
    }

    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs auth setting without a namespaceId')
    }

    // Decision 15 (Phase 2): the controller infers the scope from the (namespaceId, userId)
    // pair. user-global → only userId; user × ns → both. The userId MUST equal the
    // authenticated principal — sending another user is a 400 (mass-assignment guard).
    const payload: AuthSetting = {
      name: draft.name,
      authType: draft.authType,
      description: draft.description as string | undefined,
      data: draft.data ?? undefined,
      userId: myId,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
    }
    return this.controller.createAuthSetting(payload).pipe(tap(() => this.refresh()))
  }

  /**
   * Update an auth setting. `draft.data === null` means "user did not touch the data map" —
   * data is omitted from the payload and the backend keeps the persisted credentials (NFR-SEC-1).
   * An empty object clears all data entries.
   *
   * Build payloads explicitly — never spread `existing`. Spreading would re-inject `id`
   * (which lives in the path) and risks leaking stale fields across hypothetical scope swaps.
   * Server-side immutable fields (namespaceId, userId, authType) are preserved by the backend
   * regardless of what the body sends.
   */
  update(id: string, draft: AuthSettingDraft, scope: AuthSettingScope, existing: AuthSetting): Observable<AuthSetting> {
    const dataField = draft.data === null ? {} : { data: draft.data }

    const payload: AuthSetting = {
      name: draft.name,
      authType: draft.authType,
      description: draft.description as string | undefined,
      namespaceId: existing.namespaceId,
      userId: existing.userId,
      ...dataField,
    }
    // Reference scope to keep the signature stable for callers; the backend determines
    // the actual scope from the persisted row's (namespaceId, userId).
    void scope
    return this.controller.updateAuthSetting(id, payload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: AuthSettingScope): Observable<unknown> {
    void scope
    return this.controller.deleteAuthSetting(id).pipe(tap(() => this.refresh()))
  }
}
