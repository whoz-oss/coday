import { inject, Injectable } from '@angular/core'
import {
  AiModel,
  AiModelControllerService,
  UserAiModel,
  UserAiModelControllerService,
} from '@whoz-oss/agentos-api-client'
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

/**
 * Scope of an AI model row in the unified 3-section view. Same shape as `AiProviderScope` —
 * kept as a distinct type for clarity at call sites.
 */
export type AiModelScope = AiProviderScope

export interface AiModelConfigViewModel {
  namespace: AiModel[]
  userOnNs: UserAiModel[]
  userGlobal: UserAiModel[]
}

/**
 * Eligible parent provider as returned by `eligibleProviders$(scope)`.
 *
 * The FR3 invariant says a `UserAiModel` can only point to a parent `AiProvider` of a mode
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

const NAMESPACE_NONE_SENTINEL = 'none'
const LIST_PAGE_SIZE = 1000

/**
 * AiModelConfigStateService — orchestrates the 3 sources of truth for the unified
 * AI Models page (story 6.6, mirrors the AiProvider service which mirrors story 6.5).
 *
 * In addition to the 3-section vm$, this service exposes `eligibleProviders$(scope)` which
 * encodes the FR3 parent-mode constraint:
 *   - scope === 'namespace'  → eligible providers: namespace-shared
 *   - scope === 'userOnNs'   → eligible providers: userOnNs OR namespace-shared (same NS)
 *   - scope === 'userGlobal' → eligible providers: userGlobal only
 *
 * The form uses this to populate its provider dropdown and re-filter on scope change.
 */
@Injectable({ providedIn: 'root' })
export class AiModelConfigStateService {
  private readonly nsController = inject(AiModelControllerService)
  private readonly userController = inject(UserAiModelControllerService)
  private readonly providerState = inject(AiProviderConfigStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceId$ = new BehaviorSubject<string | null>(null)

  readonly vm$: Observable<AiModelConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      if (!namespaceId) {
        return combineLatest([
          this.loadNamespaceModels('').pipe(catchError(() => of([] as AiModel[]))),
          this.loadUserModels('global').pipe(catchError(() => of([] as UserAiModel[]))),
        ]).pipe(map(([namespace, userGlobal]) => ({ namespace, userOnNs: [] as UserAiModel[], userGlobal })))
      }
      return combineLatest([
        this.loadNamespaceModels(namespaceId).pipe(catchError(() => of([] as AiModel[]))),
        this.loadUserModels(namespaceId).pipe(catchError(() => of([] as UserAiModel[]))),
        this.loadUserModels('global').pipe(catchError(() => of([] as UserAiModel[]))),
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
   *  - 'userOnNs'   → userOnNs providers + namespace-shared providers (same namespace)
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
            return [...userOnNsItems, ...namespaceItems]
          case 'userGlobal':
            return userGlobalItems
        }
      }),
      // Avoid clearing a stale aiProviderId selection when the upstream tick produced an
      // identical list (same ids, same order).
      distinctUntilChanged(eligibleProvidersEqual)
    )
  }

  loadUserModels(scope: 'global' | string): Observable<UserAiModel[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.userController
      .listUserAiModel(namespaceParam, undefined, 0, LIST_PAGE_SIZE)
      .pipe(map((page) => page.content ?? []))
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<UserAiModel[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserModels('global'),
    [] as UserAiModel[]
  )

  loadNamespaceModels(namespaceId: string): Observable<AiModel[]> {
    if (!namespaceId) return of([])
    return this.nsController.listByNamespaceIdAiModel(namespaceId)
  }

  getById(id: string, scope: AiModelScope): Observable<AiModel | UserAiModel> {
    return scope === 'namespace' ? this.nsController.getByIdAiModel(id) : this.userController.getByIdUserAiModel(id)
  }

  create(draft: AiModelDraft, scope: AiModelScope, namespaceId: string | null): Observable<AiModel | UserAiModel> {
    if (scope === 'namespace') {
      if (!namespaceId) throw new Error('Cannot create namespace-scoped model without a namespaceId')
      const payload: AiModel = this.assembleNamespacePayload(draft, namespaceId)
      return this.nsController.createAiModel(payload).pipe(tap(() => this.refresh()))
    }
    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs model without a namespaceId')
    }
    const userPayload: UserAiModel = this.assembleUserPayload(draft, scope, namespaceId)
    return this.userController.createUserAiModel(userPayload).pipe(tap(() => this.refresh()))
  }

  update(
    id: string,
    draft: AiModelDraft,
    scope: AiModelScope,
    existing: AiModel | UserAiModel
  ): Observable<AiModel | UserAiModel> {
    if (scope === 'namespace') {
      const existingNs = (existing as AiModel).namespaceId
      if (!existingNs) {
        throw new Error(`Cannot update namespace-scoped AiModel ${id}: existing record has no namespaceId`)
      }
      const payload: AiModel = this.assembleNamespacePayload(draft, existingNs)
      return this.nsController.updateAiModel(id, payload).pipe(tap(() => this.refresh()))
    }
    const userExisting = existing as UserAiModel
    const userPayload: UserAiModel = {
      apiModelName: draft.apiModelName,
      aiProviderId: draft.aiProviderId,
      alias: draft.alias as string | undefined,
      description: draft.description as string | undefined,
      priority: draft.priority,
      temperature: draft.temperature as number | undefined,
      maxTokens: draft.maxTokens as number | undefined,
      userId: userExisting.userId,
      namespaceId: userExisting.namespaceId,
    }
    return this.userController.updateUserAiModel(id, userPayload).pipe(tap(() => this.refresh()))
  }

  delete(id: string, scope: AiModelScope): Observable<unknown> {
    const call$ =
      scope === 'namespace' ? this.nsController.deleteAiModel(id) : this.userController.deleteUserAiModel(id)
    return call$.pipe(tap(() => this.refresh()))
  }

  private assembleNamespacePayload(draft: AiModelDraft, namespaceId: string): AiModel {
    return {
      apiModelName: draft.apiModelName,
      aiProviderId: draft.aiProviderId,
      alias: draft.alias as string | undefined,
      description: draft.description as string | undefined,
      priority: draft.priority,
      temperature: draft.temperature as number | undefined,
      maxTokens: draft.maxTokens as number | undefined,
      namespaceId,
    }
  }

  private assembleUserPayload(draft: AiModelDraft, scope: AiModelScope, namespaceId: string | null): UserAiModel {
    return {
      apiModelName: draft.apiModelName,
      aiProviderId: draft.aiProviderId,
      alias: draft.alias as string | undefined,
      description: draft.description as string | undefined,
      priority: draft.priority,
      temperature: draft.temperature as number | undefined,
      maxTokens: draft.maxTokens as number | undefined,
      namespaceId: scope === 'userOnNs' ? (namespaceId as string) : undefined,
    }
  }
}
