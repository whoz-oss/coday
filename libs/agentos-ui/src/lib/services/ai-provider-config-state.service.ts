import { inject, Injectable } from '@angular/core'
import {
  AiProvider,
  AiProviderControllerService,
  UserAiProvider,
  UserAiProviderControllerService,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, shareReplay, switchMap, tap } from 'rxjs'

/**
 * Scope of an AI provider row in the unified 3-section view.
 * - `namespace` : provider shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type AiProviderScope = 'namespace' | 'userOnNs' | 'userGlobal'

export interface AiProviderConfigViewModel {
  namespace: AiProvider[]
  userOnNs: UserAiProvider[]
  userGlobal: UserAiProvider[]
}

/**
 * Shape used by callers when creating/updating a provider — the fields the user fills,
 * regardless of scope. The state service assembles the final payload (adding namespaceId /
 * userId / id / preserving masked apiKey on update) before hitting the controller.
 *
 * `description` and `apiKey` are `string | null` (no `undefined`) so that an explicit clear
 * reaches the backend as JSON null. With `undefined`, JSON.stringify omits the key and the
 * backend keeps the previous value — silent broken-clear (lesson learned from story 6.5).
 *
 * `apiKey` semantics:
 *   - non-null string  → caller wants to set the key to this value
 *   - empty string ''  → caller wants to clear the key
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

/**
 * Sentinel string accepted by the backend's `?namespaceId=` query param to filter on
 * `namespaceId IS NULL` (user-global only). Defined by `UserAiProviderController` (Kotlin).
 */
const NAMESPACE_NONE_SENTINEL = 'none'

/**
 * Page size used for the unified AI providers view. The backend exposes pagination but
 * the 3-section UI doesn't yet implement an infinite-scroll / pager — pulling a large
 * page is the pragmatic stop-gap until pagination is added (post-MVP).
 */
const LIST_PAGE_SIZE = 1000

/**
 * AiProviderConfigStateService — orchestrates the 3 sources of truth for the unified
 * AI Providers page (story 6.6, mirrors story 6.5 IntegrationConfigStateService).
 *
 *   1. NS-shared providers  → `AiProviderControllerService.listByNamespaceIdAiProvider`
 *   2. user × namespace     → `UserAiProviderControllerService.listUserAiProvider(?namespaceId=<uuid>)`
 *   3. user-global          → `UserAiProviderControllerService.listUserAiProvider(?namespaceId=none)`
 *
 * Components MUST inject this service rather than the generated controllers — that's the
 * contract from `apps/client/docs/entity-crud-pattern.md`. The service hides:
 *   - the `none` sentinel (callers speak `'global' | UUID` only)
 *   - the dispatch logic (callers pick a `scope`; the service routes to the right controller)
 *   - the refresh fan-out (a single `refresh()` re-fetches all 3 sources)
 *   - the apiKey masking semantics on update (NFR-SEC-1, FR25)
 */
@Injectable({ providedIn: 'root' })
export class AiProviderConfigStateService {
  private readonly nsController = inject(AiProviderControllerService)
  private readonly userController = inject(UserAiProviderControllerService)

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
          this.loadUserProviders('global').pipe(catchError(() => of([] as UserAiProvider[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as UserAiProvider[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceProviders(namespaceId).pipe(catchError(() => of([] as AiProvider[]))),
        this.loadUserProviders(namespaceId).pipe(catchError(() => of([] as UserAiProvider[]))),
        this.loadUserProviders('global').pipe(catchError(() => of([] as UserAiProvider[]))),
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
   * Wrapper around `listUserAiProvider` that hides the `'none'` sentinel from callers.
   * Pass `'global'` for user-global rows, or a UUID for the user × namespace slice.
   */
  loadUserProviders(scope: 'global' | string): Observable<UserAiProvider[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.userController
      .listUserAiProvider(namespaceParam, 0, LIST_PAGE_SIZE)
      .pipe(map((page) => page.content ?? []))
  }

  loadNamespaceProviders(namespaceId: string): Observable<AiProvider[]> {
    if (!namespaceId) return of([])
    return this.nsController.listByNamespaceIdAiProvider(namespaceId)
  }

  /**
   * Fetch a single provider by id, dispatching on scope.
   * Used by the form for hydration from `?template=<id>` (cross-link override).
   */
  getById(id: string, scope: AiProviderScope): Observable<AiProvider | UserAiProvider> {
    return scope === 'namespace'
      ? this.nsController.getByIdAiProvider(id)
      : this.userController.getByIdUserAiProvider(id)
  }

  create(
    draft: AiProviderDraft,
    scope: AiProviderScope,
    namespaceId: string | null
  ): Observable<AiProvider | UserAiProvider> {
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
    const userPayload: UserAiProvider = {
      name: draft.name,
      apiType: draft.apiType as unknown as UserAiProvider['apiType'],
      description: draft.description as string | undefined,
      baseUrl: draft.baseUrl as string | undefined,
      apiKey: draft.apiKey as string | undefined,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
    }
    return this.userController.createUserAiProvider(userPayload).pipe(tap(() => this.refresh()))
  }

  /**
   * Update a provider. `draft.apiKey === null` means "user did not touch the field" — the
   * apiKey is omitted from the payload and the backend keeps the persisted credential (FR25,
   * NFR-SEC-1; pattern from PR #811 on `AiProviderController.update`). Passing an empty string
   * means "clear the key" (sent as JSON null).
   *
   * Build payloads explicitly — never spread `existing`. Spreading would re-inject `id`
   * (which lives in the path) and risks leaking stale fields across hypothetical scope swaps.
   * Server-side immutable fields (namespaceId, userId) are preserved by reading them from `existing`.
   */
  update(
    id: string,
    draft: AiProviderDraft,
    scope: AiProviderScope,
    existing: AiProvider | UserAiProvider
  ): Observable<AiProvider | UserAiProvider> {
    const apiKeyField =
      draft.apiKey === null ? {} : { apiKey: draft.apiKey === '' ? undefined : (draft.apiKey as string) }

    if (scope === 'namespace') {
      const payload: AiProvider = {
        name: draft.name,
        apiType: draft.apiType,
        description: draft.description as string | undefined,
        baseUrl: draft.baseUrl as string | undefined,
        namespaceId: (existing as AiProvider).namespaceId,
        ...apiKeyField,
      }
      return this.nsController.updateAiProvider(id, payload).pipe(tap(() => this.refresh()))
    }
    const userExisting = existing as UserAiProvider
    const userPayload: UserAiProvider = {
      name: draft.name,
      apiType: draft.apiType as unknown as UserAiProvider['apiType'],
      description: draft.description as string | undefined,
      baseUrl: draft.baseUrl as string | undefined,
      userId: userExisting.userId,
      namespaceId: userExisting.namespaceId,
      ...apiKeyField,
    }
    return this.userController.updateUserAiProvider(id, userPayload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: AiProviderScope): Observable<unknown> {
    const call$ =
      scope === 'namespace' ? this.nsController.deleteAiProvider(id) : this.userController.deleteUserAiProvider(id)
    return call$.pipe(tap(() => this.refresh()))
  }
}
