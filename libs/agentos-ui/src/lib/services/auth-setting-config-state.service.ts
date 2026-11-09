import { inject, Injectable } from '@angular/core'
import {
  ApiKeyAuthSetting,
  AuthSettingConnection,
  AuthSettingControllerService,
  AuthSettingDto,
  BasicAuthAuthSetting,
  BearerTokenAuthSetting,
  OAuthCustomAuthSetting,
  OAuthDiscoverableAuthSetting,
  OAuthMcpDiscoverableAuthSetting,
  OAuthRegisteredAuthSetting,
} from '@whoz-oss/agentos-api-client'
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  filter,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  take,
  tap,
  timer,
} from 'rxjs'
import { multicastRefreshable } from './rxjs-state.utils'
import { UserStateService } from './user-state.service'

/**
 * Scope of an auth setting row in the unified 4-section view.
 * - `platform`  : setting defined at platform level (read-only for namespace admins)
 * - `namespace` : setting shared at the namespace level
 * - `userOnNs`  : the caller's personal override scoped to the current namespace
 * - `userGlobal`: the caller's personal override that applies cross-namespace
 */
export type AuthSettingScope = 'platform' | 'namespace' | 'userOnNs' | 'userGlobal'

export interface AuthSettingConfigViewModel {
  platform: AuthSettingDto[]
  namespace: AuthSettingDto[]
  userOnNs: AuthSettingDto[]
  userGlobal: AuthSettingDto[]
}

/**
 * Payload for creating or updating an auth setting.
 * The form builds the full typed union member — the state service passes it straight
 * through to the controller, augmenting only with scope-derived (namespaceId, userId).
 *
 * Masking (NFR-SEC-1): credential fields that the user left unchanged from the server
 * sentinel are set to `undefined` by the form before calling create/update, so the
 * backend keeps the persisted value.
 */
export type AuthSettingPayload =
  | Omit<ApiKeyAuthSetting, 'authType'>
  | Omit<BasicAuthAuthSetting, 'authType'>
  | Omit<BearerTokenAuthSetting, 'authType'>
  | Omit<OAuthCustomAuthSetting, 'authType'>
  | Omit<OAuthDiscoverableAuthSetting, 'authType'>
  | Omit<OAuthMcpDiscoverableAuthSetting, 'authType'>
  | Omit<OAuthRegisteredAuthSetting, 'authType'>

/** The authType discriminant values as a union of string literals. */
export type AuthSettingType = AuthSettingDto['authType']

/** Ordered list of all auth setting types for UI selectors. */
export const AUTH_SETTING_TYPES: ReadonlyArray<AuthSettingType> = [
  'ApiKeyAuthSetting',
  'BasicAuthAuthSetting',
  'BearerTokenAuthSetting',
  'OAuthDiscoverableAuthSetting',
  'OAuthMcpDiscoverableAuthSetting',
  'OAuthCustomAuthSetting',
  'OAuthRegisteredAuthSetting',
] as const

/** Human-readable label for each auth type. */
export const AUTH_SETTING_TYPE_LABEL: Readonly<Record<AuthSettingType, string>> = {
  ApiKeyAuthSetting: 'API Key',
  BasicAuthAuthSetting: 'Basic Auth (username / password)',
  BearerTokenAuthSetting: 'Bearer Token',
  OAuthDiscoverableAuthSetting: 'OAuth — Discovery URL',
  OAuthMcpDiscoverableAuthSetting: 'OAuth — MCP Discoverable',
  OAuthCustomAuthSetting: 'OAuth — Custom endpoints',
  OAuthRegisteredAuthSetting: 'OAuth — Registered app',
}

/** Sentinel accepted by the backend's `?namespaceId=` to filter on `namespaceId IS NULL`. */
const NAMESPACE_NONE_SENTINEL = 'none'
/** Sentinel accepted by the backend's `?userId=` to mean "the authenticated user". */
const USER_ME_SENTINEL = 'me'

/** How often the connection state is read while the user authorizes in the popup. */
export const CONNECTION_POLL_MS = 2000
/** How long a connection is awaited: a little more than the backend gives the flow (2 minutes by default). */
export const CONNECTION_WAIT_MS = 150_000
/** Polls to wait, once the popup is closed, for the backend to finish exchanging the code. */
const CONNECTION_CLOSED_GRACE_POLLS = 3

/**
 * How an authorization ended, as seen from the browser:
 * - `connected`: a new connection was stored;
 * - `abandoned`: the popup was closed and nothing new appeared;
 * - `timeout`: nothing appeared within [CONNECTION_WAIT_MS].
 */
export type ConnectionOutcome = 'connected' | 'abandoned' | 'timeout'

// ── authType discriminant mapping ────────────────────────────────────────────
//
// The OpenAPI generator uses schema class names as discriminant values, but Jackson
// on the backend uses @JsonSubTypes names (SCREAMING_SNAKE_CASE). These two maps
// translate between the two representations at the HTTP boundary.

/** TS generated discriminant → Jackson @JsonSubTypes name sent to the backend. */
const AUTH_TYPE_TO_BACKEND: Readonly<Record<string, string>> = {
  ApiKeyAuthSetting: 'API_KEY',
  BasicAuthAuthSetting: 'BASIC_AUTH',
  BearerTokenAuthSetting: 'BEARER_TOKEN',
  OAuthDiscoverableAuthSetting: 'OAUTH_DISCOVERABLE',
  OAuthMcpDiscoverableAuthSetting: 'OAUTH_MCP_DISCOVERABLE',
  OAuthCustomAuthSetting: 'OAUTH_CUSTOM',
  OAuthRegisteredAuthSetting: 'OAUTH_REGISTERED',
}

/** Jackson @JsonSubTypes name received from the backend → TS generated discriminant. */
const AUTH_TYPE_FROM_BACKEND: Readonly<Record<string, string>> = {
  API_KEY: 'ApiKeyAuthSetting',
  BASIC_AUTH: 'BasicAuthAuthSetting',
  BEARER_TOKEN: 'BearerTokenAuthSetting',
  OAUTH_DISCOVERABLE: 'OAuthDiscoverableAuthSetting',
  OAUTH_MCP_DISCOVERABLE: 'OAuthMcpDiscoverableAuthSetting',
  OAUTH_CUSTOM: 'OAuthCustomAuthSetting',
  OAUTH_REGISTERED: 'OAuthRegisteredAuthSetting',
}

/**
 * Translate the TS discriminant to the Jackson name before sending to the backend.
 * If the value is already a backend name (or unknown), it passes through unchanged.
 */
function toBackendDto(dto: AuthSettingDto): AuthSettingDto {
  const mapped = AUTH_TYPE_TO_BACKEND[dto.authType]
  return mapped ? { ...dto, authType: mapped as AuthSettingDto['authType'] } : dto
}

/**
 * Casts the opaque `GetByIdAuthSetting200Response` (an empty interface generated by the
 * OpenAPI generator when it cannot express a polymorphic response) to the concrete union
 * type, normalizing the backend Jackson discriminant back to the TS generated value.
 * Safe: the backend always returns one of the AuthSettingDto members.
 */
function castToDto(raw: object): AuthSettingDto {
  return fromBackendRaw(raw)
}

function castToDtoArray(raw: object[]): AuthSettingDto[] {
  return raw.map(fromBackendRaw)
}

/** Normalize a raw backend object's authType from Jackson name to TS discriminant. */
function fromBackendRaw(raw: object): AuthSettingDto {
  const r = raw as Record<string, unknown>
  if (typeof r['authType'] === 'string') {
    const mapped = AUTH_TYPE_FROM_BACKEND[r['authType']]
    if (mapped) return { ...r, authType: mapped } as AuthSettingDto
  }
  return raw as AuthSettingDto
}

/** Whether [connectedAt] is after [since]; any connection counts when there was none before. */
function isNewerThan(connectedAt: string | undefined, since: string | null): boolean {
  if (since === null) return true
  return connectedAt !== undefined && Date.parse(connectedAt) > Date.parse(since)
}

/**
 * AuthSettingConfigStateService — orchestrates the 4 scope layers for the unified
 * Auth Settings page (Issue #1095). All scope-specific calls use
 * `AuthSettingControllerService.listAuthSetting(namespaceId, userId)`, with the scope
 * distinguished by query params:
 *
 *   1. platform         → `listAuthSetting(namespaceId='none')` (no `userId`)
 *   2. NS-shared        → `listAuthSetting(namespaceId=<uuid>)` (no `userId`)
 *   3. user × namespace → `listAuthSetting(namespaceId=<uuid>, userId='me')`
 *   4. user-global      → `listAuthSetting(namespaceId='none', userId='me')`
 *
 * Calling without parameters returns all of the caller's personal overlays, not the
 * platform layer. Without a selected namespace, only platform and user-global are fetched.
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
   * calls instead of repeating each scope's GET. Per-source `catchError` keeps the page
   * rendering when one layer fails — a 5xx on user-global must not blank the namespace section.
   */
  readonly vm$: Observable<AuthSettingConfigViewModel> = combineLatest([this.namespaceId$, this.refresh$]).pipe(
    switchMap(([namespaceId]) => {
      const platform$ = this.loadPlatformSettings().pipe(catchError(() => of([] as AuthSettingDto[])))
      if (!namespaceId) {
        return combineLatest([
          platform$,
          this.loadNamespaceSettings('').pipe(catchError(() => of([] as AuthSettingDto[]))),
          this.loadUserSettings('global').pipe(catchError(() => of([] as AuthSettingDto[]))),
        ]).pipe(
          map(([platform, namespace, userGlobal]) => ({
            platform,
            namespace,
            userOnNs: [] as AuthSettingDto[],
            userGlobal,
          }))
        )
      }
      return combineLatest([
        platform$,
        this.loadNamespaceSettings(namespaceId).pipe(catchError(() => of([] as AuthSettingDto[]))),
        this.loadUserSettings(namespaceId).pipe(catchError(() => of([] as AuthSettingDto[]))),
        this.loadUserSettings('global').pipe(catchError(() => of([] as AuthSettingDto[]))),
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

  loadUserSettings(scope: 'global' | string): Observable<AuthSettingDto[]> {
    const namespaceParam = scope === 'global' ? NAMESPACE_NONE_SENTINEL : scope
    return this.controller.listAuthSetting(namespaceParam, USER_ME_SENTINEL).pipe(map((raw) => castToDtoArray(raw)))
  }

  /** User-global slice consumed by `UserProfileComponent.recap` — see `multicastRefreshable`. */
  readonly userGlobal$: Observable<AuthSettingDto[]> = multicastRefreshable(
    this.refresh$,
    () => this.loadUserSettings('global'),
    [] as AuthSettingDto[]
  )

  /** Platform-level auth settings: explicit namespaceId=none, no userId. */
  loadPlatformSettings(): Observable<AuthSettingDto[]> {
    return this.controller.listAuthSetting(NAMESPACE_NONE_SENTINEL).pipe(map((raw) => castToDtoArray(raw)))
  }

  loadNamespaceSettings(namespaceId: string): Observable<AuthSettingDto[]> {
    if (!namespaceId) return of([])
    return this.controller.listAuthSetting(namespaceId).pipe(map((raw) => castToDtoArray(raw)))
  }

  /**
   * Fetch a single auth setting by id. The unified `GET /api/auth-settings/{id}` route
   * handles all scopes — the evaluator's ownership branch decides authz.
   */
  getById(id: string): Observable<AuthSettingDto>
  getById(id: string, scope: AuthSettingScope): Observable<AuthSettingDto>
  getById(id: string, _scope?: AuthSettingScope): Observable<AuthSettingDto> {
    return this.controller.getByIdAuthSetting(id).pipe(map((raw) => castToDto(raw)))
  }

  /**
   * Create an auth setting. The form provides the full typed DTO member; the state service
   * augments it with (namespaceId, userId) derived from scope.
   *
   * `authType` in `typed` is the discriminant string literal — it is already present in the
   * object the form constructs and is passed straight through to the controller.
   */
  create(typed: AuthSettingDto, scope: AuthSettingScope, namespaceId: string | null): Observable<AuthSettingDto> {
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
    }
    if (scope === 'userOnNs' && !namespaceId) {
      throw new Error('Cannot create userOnNs auth setting without a namespaceId')
    }

    const payload: AuthSettingDto = toBackendDto({
      ...typed,
      // null → platform scope (namespaceId IS NULL); string UUID → namespace / user×ns scope
      namespaceId:
        scope === 'namespace' || scope === 'userOnNs'
          ? (namespaceId ?? undefined)
          : scope === 'platform'
            ? undefined
            : undefined,
      userId: scope === 'userOnNs' || scope === 'userGlobal' ? myId : undefined,
    })
    return this.controller.createAuthSetting(payload).pipe(
      map((raw) => castToDto(raw)),
      tap(() => this.refresh())
    )
  }

  /**
   * Update an auth setting. The form provides the full typed DTO member with credential
   * fields already masked (unchanged values set to `undefined`).
   *
   * Server-side immutable fields (namespaceId, userId, authType) are preserved from
   * the existing resource — never taken from the form.
   */
  update(id: string, typed: AuthSettingDto, existing: AuthSettingDto): Observable<AuthSettingDto> {
    const payload: AuthSettingDto = toBackendDto({
      ...typed,
      // Preserve server-side immutable identity fields from the loaded resource.
      namespaceId: existing.namespaceId,
      userId: existing.userId,
      // authType is always taken from `existing` (server-authoritative) then translated
      // to the backend discriminant by toBackendDto before the request is sent.
      authType: existing.authType,
    })
    return this.controller.updateAuthSetting(id, payload).pipe(
      map((raw) => castToDto(raw)),
      tap(() => this.refresh())
    )
  }

  delete(id: string, scope: AuthSettingScope): Observable<unknown> {
    void scope
    return this.controller.deleteAuthSetting(id).pipe(tap(() => this.refresh()))
  }

  /**
   * Whether the caller holds a usable connection to an OAuth auth setting. Never carries a token.
   *
   * Pass the namespace the setting is used from: a connection belongs to the layer the runtime resolves the
   * setting name to, so two rows with the same name share one connection.
   */
  getConnection(id: string, namespaceId?: string): Observable<AuthSettingConnection> {
    return this.controller.getConnectionAuthSetting(id, namespaceId)
  }

  /**
   * Forget the caller's own stored connection (OAuth tokens) to an auth setting, so the next use of an
   * integration bound to it asks for a fresh authorization. Personal to the caller whatever the scope of
   * the setting; idempotent. No list refresh: the rows do not carry the connection state.
   */
  resetCredential(id: string, namespaceId?: string): Observable<unknown> {
    return this.controller.resetMyCredentialAuthSetting(id, namespaceId)
  }

  /**
   * Connects the caller to an OAuth auth setting: starts the authorization on the backend, hands its URL to
   * [authorize] (which points the popup at it), then waits for a new connection to be stored.
   *
   * The popup reports nothing reliable (the callback answers before the token exchange), so success is read
   * from the connection itself: it must be newer than the one that existed when the flow started, which also
   * makes a reconnection distinguishable from the connection it replaces. [abandoned] tells whether the user
   * closed the popup.
   */
  connect(
    id: string,
    namespaceId: string | undefined,
    authorize: (authorizationUrl: string) => void,
    abandoned: () => boolean
  ): Observable<ConnectionOutcome> {
    // The state before comes first and must be readable: without it a reconnection cannot be told from the
    // connection it replaces, and starting the flow anyway would leave it pending with nobody to finish it.
    return this.getConnection(id, namespaceId).pipe(
      switchMap((before) =>
        this.controller.connectAuthSetting(id, namespaceId).pipe(
          tap((started) => authorize(started.authorizationUrl)),
          switchMap(() =>
            this.awaitNewConnection(id, namespaceId, before.connected ? (before.connectedAt ?? null) : null, abandoned)
          )
        )
      )
    )
  }

  /** Polls the connection until it is newer than [since] (any connection when null), abandoned, or too late. */
  awaitNewConnection(
    id: string,
    namespaceId: string | undefined,
    since: string | null,
    abandoned: () => boolean
  ): Observable<ConnectionOutcome> {
    let polls = 0
    let closedPolls = 0
    return timer(CONNECTION_POLL_MS, CONNECTION_POLL_MS).pipe(
      // A failed read is not an answer: keep waiting rather than reporting a failure that did not happen.
      switchMap(() => this.getConnection(id, namespaceId).pipe(catchError(() => of(null)))),
      map((connection): ConnectionOutcome | null => {
        polls++
        if (connection?.connected && isNewerThan(connection.connectedAt, since)) return 'connected'
        if (polls * CONNECTION_POLL_MS >= CONNECTION_WAIT_MS) return 'timeout'
        if (abandoned() && ++closedPolls >= CONNECTION_CLOSED_GRACE_POLLS) return 'abandoned'
        return null
      }),
      filter((outcome): outcome is ConnectionOutcome => outcome !== null),
      take(1)
    )
  }
}
