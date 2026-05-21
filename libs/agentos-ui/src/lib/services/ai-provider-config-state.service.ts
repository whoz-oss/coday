import { inject, Injectable } from '@angular/core'
import { AiProvider, AiProviderControllerService } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, shareReplay, switchMap, tap } from 'rxjs'
import { multicastRefreshable } from './rxjs-state.utils'
import { UserStateService } from './user-state.service'

/**
 * Scope of an AI provider row in the unified 3-section view.
 * - `namespace` : provider shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type AiProviderScope = 'namespace' | 'userOnNs' | 'userGlobal'

export interface AiProviderConfigViewModel {
  namespace: AiProvider[]
  userOnNs: AiProvider[]
  userGlobal: AiProvider[]
}

/**
 * Shape used by callers when creating/updating a provider — the fields the user fills,
 * regardless of scope. The state service assembles the final payload (adding namespaceId /
 * userId / id / preserving masked apiKey on update) before hitting the controller.
 *
 * `description` and `apiKey` are `string | null` (no `undefined`) so the state service can
 * distinguish "untouched" from "deliberately cleared". With `undefined`, JSON.stringify omits
 * the key and the backend keeps the previous value — silent broken-clear (lesson learned from
 * story 6.5).
 *
 * `apiKey` semantics:
 *   - non-blank string → caller wants to set the key to this value
 *   - empty string ''  → caller wants to clear the key (sent on the wire as `apiKey: ""`;
 *                         the backend treats blank as explicit clear and persists null)
 *   - null             → caller did NOT touch the field (keep previous value);
 *                         the state service omits apiKey from the update payload (FR25 / NFR-SEC-1)
 */
export interface AiProviderDraft {
  name: string
  apiType: AiProvider['apiType']
  description: string | null
  baseUrl: string | null
  apiKey: string | null
}

/** Sentinel accepted by the backend's `?namespaceId=` to filter on `namespaceId IS NULL`. */
const NAMESPACE_NONE_SENTINEL = 'none'
/** Sentinel accepted by the backend's `?userId=` to mean "the authenticated user". */
const USER_ME_SENTINEL = 'me'

/**
 * AiProviderConfigStateService — orchestrates the 3 sources of truth for the unified
 * AI Providers page. Post-PR-#838 (`unify-ns-user-crud-controllers`) all 3 calls land on
 * the single `AiProviderControllerService.listAiProvider(namespaceId, userId, …)`, with the
 * shape distinguished by query params :
 *
 *   1. NS-shared        → `listAiProvider(namespaceId=<uuid>)` (no `userId`)
 *   2. user × namespace → `listAiProvider(namespaceId=<uuid>, userId='me')`
 *   3. user-global      → `listAiProvider(namespaceId='none', userId='me')`
 *
 * The implicit-scope dispatch on `POST` (Decision 15) lives server-side ; on the FE the
 * payload's `(namespaceId, userId)` pair encodes the intent — the create method assembles
 * it explicitly per scope.
 */
@Injectable({ providedIn: 'root' })
export class AiProviderConfigStateService {
  private readonly nsController = inject(AiProviderControllerService)
  private readonly userState = inject(UserStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  /**
   * Reactive view model for the all-scopes page. Multicast via `shareReplay` so concurrent
   * subscribers (template async pipe + ngOnInit derivations) share a single fan-out of HTTP
   * calls instead of redoing all 3 GETs each. Per-source `catchError` keeps the page rendering
   * when one of the 3 layers fails — a 5xx on user-global must not blank the namespace section.
   */
  readonly vm$: Observable<AiProviderConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      if (!namespaceId) {
        return combineLatest([
          this.loadNamespaceProviders('').pipe(catchError(() => of([] as AiProvider[]))),
          this.loadUserProviders('global').pipe(catchError(() => of([] as AiProvider[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as AiProvider[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceProviders(namespaceId).pipe(catchError(() => of([] as AiProvider[]))),
        this.loadUserProviders(namespaceId).pipe(catchError(() => of([] as AiProvider[]))),
        this.loadUserProviders('global').pipe(catchError(() => of([] as AiProvider[]))),
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
   * Wrapper around the unified `listAiProvider` for the user-overlay slices. Callers speak
   * `'global'` (user-global rows : `namespaceId IS NULL`) or a concrete UUID (user × namespace
   * for that namespace). The `'none'` / `'me'` sentinels stay private.
   */
  loadUserProviders(scope: 'global' | string): Observable<AiProvider[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.nsController.listAiProvider(namespaceParam, USER_ME_SENTINEL)
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<AiProvider[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserProviders('global'),
    [] as AiProvider[]
  )

  loadNamespaceProviders(namespaceId: string): Observable<AiProvider[]> {
    if (!namespaceId) return of([])
    // NS-shared : `userId` omitted → backend serves the namespace-scoped layer for that NS.
    return this.nsController.listAiProvider(namespaceId)
  }

  /**
   * Fetch a single provider by id. With the unified controller all scopes share the same
   * `GET /api/ai-providers/{id}` route — the evaluator's ownership branch decides authz.
   * The legacy two-overload `getById(id, scope)` is kept for back-compat with components
   * that still pass a scope parameter ; it now ignores `scope` and delegates to the unified
   * call, which simplifies `tryLoadAcrossScopes` in the form component (G10).
   */
  getById(id: string): Observable<AiProvider>
  getById(id: string, scope: AiProviderScope): Observable<AiProvider>
  getById(id: string, _scope?: AiProviderScope): Observable<AiProvider> {
    return this.nsController.getByIdAiProvider(id)
  }

  create(draft: AiProviderDraft, scope: AiProviderScope, namespaceId: string | null): Observable<AiProvider> {
    const me = this.userState.currentUser()
    const myId = me?.id
    if ((scope === 'userOnNs' || scope === 'userGlobal') && !myId) {
      throw new Error(
        `Cannot create ${scope} provider before UserStateService.loadMe() resolves — call loadMe() at app init`
      )
    }

    if (scope === 'namespace') {
      if (!namespaceId) throw new Error('Cannot create namespace-scoped provider without a namespaceId')
      const payload: AiProvider = {
        name: draft.name,
        apiType: draft.apiType,
        description: draft.description as string | undefined,
        baseUrl: draft.baseUrl as string | undefined,
        apiKey: draft.apiKey as string | undefined,
        namespaceId,
      }
      return this.nsController.createAiProvider(payload).pipe(tap(() => this.refresh()))
    }

    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs provider without a namespaceId')
    }

    // Decision 15 (Phase 2): the controller infers the scope from the (namespaceId, userId)
    // pair. user-global → only userId ; user × ns → both. The userId MUST equal the
    // authenticated principal — sending another user is a 400 (mass-assignment guard).
    const payload: AiProvider = {
      name: draft.name,
      apiType: draft.apiType,
      description: draft.description as string | undefined,
      baseUrl: draft.baseUrl as string | undefined,
      apiKey: draft.apiKey as string | undefined,
      userId: myId,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
    }
    return this.nsController.createAiProvider(payload).pipe(tap(() => this.refresh()))
  }

  /**
   * Update a provider. `draft.apiKey === null` means "user did not touch the field" — the
   * apiKey is omitted from the payload and the backend keeps the persisted credential (FR25,
   * NFR-SEC-1). Empty string clears the key.
   *
   * Build payloads explicitly — never spread `existing`. Spreading would re-inject `id`
   * (which lives in the path) and risks leaking stale fields across hypothetical scope swaps.
   * Server-side immutable fields (namespaceId, userId, apiType) are preserved by the backend
   * regardless of what the body sends.
   */
  update(id: string, draft: AiProviderDraft, scope: AiProviderScope, existing: AiProvider): Observable<AiProvider> {
    const apiKeyField = draft.apiKey === null ? {} : { apiKey: draft.apiKey }

    const payload: AiProvider = {
      name: draft.name,
      apiType: draft.apiType,
      description: draft.description as string | undefined,
      baseUrl: draft.baseUrl as string | undefined,
      namespaceId: existing.namespaceId,
      userId: existing.userId,
      ...apiKeyField,
    }
    // Reference scope to keep the signature stable for callers ; the backend determines
    // the actual scope from the persisted row's (namespaceId, userId).
    void scope
    return this.nsController.updateAiProvider(id, payload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: AiProviderScope): Observable<unknown> {
    void scope
    return this.nsController.deleteAiProvider(id).pipe(tap(() => this.refresh()))
  }
}
