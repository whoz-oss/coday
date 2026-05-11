import { inject, Injectable } from '@angular/core'
import { AiModel, AiModelControllerService } from '@whoz-oss/agentos-api-client'
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  distinctUntilChanged,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  tap,
} from 'rxjs'
import { AiProviderConfigStateService, AiProviderScope } from './ai-provider-config-state.service'
import { multicastRefreshable } from './rxjs-state.utils'
import { UserStateService } from './user-state.service'

/**
 * Scope of an AI model row in the unified 3-section view. Same shape as `AiProviderScope` —
 * kept as a distinct type for clarity at call sites.
 */
export type AiModelScope = AiProviderScope

export interface AiModelConfigViewModel {
  namespace: AiModel[]
  userOnNs: AiModel[]
  userGlobal: AiModel[]
}

/**
 * Eligible parent provider as returned by `eligibleProviders$(scope)`.
 *
 * The FR3 invariant says an AiModel can only point to a parent AiProvider of a mode
 * compatible with its own scope. The service translates this rule into a flat list with the
 * provider scope embedded so the form can label each option.
 */
export interface EligibleProvider {
  id: string
  name: string
  scope: AiProviderScope
}

function eligibleProvidersEqual(a: EligibleProvider[], b: EligibleProvider[]): boolean {
  return a.length === b.length && a.every((x, i) => x.id === b[i]?.id && x.scope === b[i]?.scope)
}

/**
 * Caller-facing draft. `apiKey` is not part of an AiModel — only the parent provider holds
 * the credential. We keep `description` as `string | null` so an explicit clear actually
 * reaches the backend (lesson learned from story 6.5: `undefined` makes JSON.stringify drop
 * the key, the backend keeps the previous value).
 */
export interface AiModelDraft {
  apiModelName: string
  alias: string | null
  description: string | null
  priority: number
  temperature: number | null
  maxTokens: number | null
  aiProviderId: string
}

/** Sentinel accepted by the backend's `?namespaceId=` to filter on `namespaceId IS NULL`. */
const NAMESPACE_NONE_SENTINEL = 'none'
/** Sentinel accepted by the backend's `?userId=` to mean "the authenticated user". */
const USER_ME_SENTINEL = 'me'

const LIST_PAGE_SIZE = 1000

/**
 * AiModelConfigStateService — orchestrates the 3 sources of truth for the unified
 * AI Models page (Decision 19 follow-up). Post-unification all 3 calls land on the single
 * `AiModelControllerService.listAiModel(namespaceId, userId, …)`, with scope distinguished
 * by query params:
 *
 *   1. NS-shared        → `listAiModel(namespaceId=<uuid>)` (no `userId`)
 *   2. user × namespace → `listAiModel(namespaceId=<uuid>, userId='me')`
 *   3. user-global      → `listAiModel(namespaceId='none', userId='me')`
 *
 * Scope on POST is server-inferred from the parent `aiProviderId` — the payload only needs
 * `aiProviderId + apiModelName + …` ; `namespaceId` / `userId` are denormalized server-side.
 */
@Injectable({ providedIn: 'root' })
export class AiModelConfigStateService {
  private readonly controller = inject(AiModelControllerService)
  private readonly userState = inject(UserStateService)
  private readonly providerState = inject(AiProviderConfigStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  readonly vm$: Observable<AiModelConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      if (!namespaceId) {
        return combineLatest([
          this.loadNamespaceModels('').pipe(catchError(() => of([] as AiModel[]))),
          this.loadUserModels('global').pipe(catchError(() => of([] as AiModel[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as AiModel[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceModels(namespaceId).pipe(catchError(() => of([] as AiModel[]))),
        this.loadUserModels(namespaceId).pipe(catchError(() => of([] as AiModel[]))),
        this.loadUserModels('global').pipe(catchError(() => of([] as AiModel[]))),
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
   * Reactive list of providers that are valid parents for an AiModel of the given scope
   * (FR3). Re-emits whenever the underlying provider VM changes.
   *
   *  - 'namespace'  → namespace-shared providers only
   *  - 'userOnNs'   → userOnNs providers only (strict scope match : an AiModel's scope is
   *                   denormalized from its parent AiProvider at create time, so a userOnNs
   *                   model must attach to a userOnNs provider — see AiModelServiceImpl.create)
   *  - 'userGlobal' → userGlobal providers only
   */
  eligibleProviders$(scope: AiModelScope): Observable<EligibleProvider[]> {
    return this.providerState.vm$.pipe(
      map((providerVm) => {
        const namespaceItems = providerVm.namespace
          .filter((p): p is typeof p & { id: string } => !!p.id)
          .map((p) => ({ id: p.id, name: p.name, scope: 'namespace' as const }))
        const userOnNsItems = providerVm.userOnNs
          .filter((p): p is typeof p & { id: string } => !!p.id)
          .map((p) => ({ id: p.id, name: p.name, scope: 'userOnNs' as const }))
        const userGlobalItems = providerVm.userGlobal
          .filter((p): p is typeof p & { id: string } => !!p.id)
          .map((p) => ({ id: p.id, name: p.name, scope: 'userGlobal' as const }))

        switch (scope) {
          case 'namespace':
            return namespaceItems
          case 'userOnNs':
            return userOnNsItems
          case 'userGlobal':
            return userGlobalItems
        }
      }),
      distinctUntilChanged(eligibleProvidersEqual)
    )
  }

  /**
   * Wrapper around the unified `listAiModel` for the user-overlay slices. Callers speak
   * `'global'` (user-global rows : `namespaceId IS NULL`) or a concrete UUID (user × namespace
   * for that namespace). The `'none'` / `'me'` sentinels stay private.
   */
  loadUserModels(scope: 'global' | string): Observable<AiModel[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.controller
      .listAiModel(namespaceParam, USER_ME_SENTINEL, undefined, 0, LIST_PAGE_SIZE)
      .pipe(map((page) => page.content ?? []))
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<AiModel[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserModels('global'),
    [] as AiModel[]
  )

  loadNamespaceModels(namespaceId: string): Observable<AiModel[]> {
    if (!namespaceId) return of([])
    return this.controller
      .listAiModel(namespaceId, undefined, undefined, 0, LIST_PAGE_SIZE)
      .pipe(map((page) => page.content ?? []))
  }

  /**
   * Fetch a single model by id. With the unified controller all scopes share the same
   * `GET /api/ai-models/{id}` route — the evaluator's ownership branch decides authz.
   * The legacy two-overload `getById(id, scope)` is kept for back-compat with components
   * that still pass a scope parameter ; it now ignores `scope` and delegates to the unified
   * call.
   */
  getById(id: string): Observable<AiModel>
  getById(id: string, scope: AiModelScope): Observable<AiModel>
  getById(id: string, _scope?: AiModelScope): Observable<AiModel> {
    return this.controller.getByIdAiModel(id)
  }

  create(draft: AiModelDraft, scope: AiModelScope, _namespaceId: string | null): Observable<AiModel> {
    const me = this.userState.currentUser()
    const myId = me?.id
    if ((scope === 'userOnNs' || scope === 'userGlobal') && !myId) {
      throw new Error(
        `Cannot create ${scope} model before UserStateService.loadMe() resolves — call loadMe() at app init`
      )
    }
    // Scope is server-inferred from the parent aiProvider's scope. The payload only needs
    // aiProviderId — namespaceId and userId are denormalized server-side (Decision 19 / SF4).
    const payload: AiModel = {
      aiProviderId: draft.aiProviderId,
      apiModelName: draft.apiModelName,
      alias: draft.alias as string | undefined,
      description: draft.description as string | undefined,
      priority: draft.priority,
      temperature: draft.temperature as number | undefined,
      maxTokens: draft.maxTokens as number | undefined,
    }
    return this.controller.createAiModel(payload).pipe(tap(() => this.refresh()))
  }

  update(id: string, draft: AiModelDraft, scope: AiModelScope, existing: AiModel): Observable<AiModel> {
    const payload: AiModel = {
      apiModelName: draft.apiModelName,
      aiProviderId: existing.aiProviderId,
      alias: draft.alias as string | undefined,
      description: draft.description as string | undefined,
      priority: draft.priority,
      temperature: draft.temperature as number | undefined,
      maxTokens: draft.maxTokens as number | undefined,
      namespaceId: existing.namespaceId,
      userId: existing.userId,
    }
    void scope
    return this.controller.updateAiModel(id, payload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: AiModelScope): Observable<unknown> {
    void scope
    return this.controller.deleteAiModel(id).pipe(tap(() => this.refresh()))
  }
}
