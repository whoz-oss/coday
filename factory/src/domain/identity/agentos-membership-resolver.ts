/**
 * AgentOS directory-backed membership resolver.
 *
 * `AgentOsMembershipResolver` is the bridge between the authoritative AgentOS
 * directory (UserGroups / namespace membership, where a member role is either
 * `ADMIN` or `MEMBER`) and the Factory `MembershipInfo` vocabulary
 * (`organizationId`, `workstreamId`, `squadId`, `roles`).
 *
 * Design constraints (Milestone B, wave B6):
 *   - The resolver NEVER performs I/O itself: the AgentOS directory client is
 *     injected and the resolver only maps its answers. This keeps the domain
 *     pure and the HTTP boundary testable offline.
 *   - Resolution is **fail-closed**: any directory failure, unknown principal
 *     or malformed response yields `EMPTY_MEMBERSHIP` (no privilege). The
 *     resolver never throws on the resolution path.
 *   - Resolved memberships are cached in memory with a configurable TTL. A
 *     cache hit is answered synchronously (non-thenable), so it flows through
 *     the synchronous HTTP boundary (`resolveMembershipSync`) without change.
 *   - Service principals are isolated: they always receive the
 *     `service-runner` role and can never be granted `admin`.
 *
 * Domain purity: this module has no I/O dependency.
 */

// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import type { PrincipalType } from './trust-context.ts'
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import type { MembershipInfo, MembershipResolver } from './membership-resolver.ts'
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import { EMPTY_MEMBERSHIP } from './membership-resolver.ts'

/** Default organization used when the instance configures none. */
export const DEFAULT_AGENTOS_ORGANIZATION_ID = 'org-local-dev'

/** Default membership cache TTL, in milliseconds. */
export const DEFAULT_AGENTOS_MEMBERSHIP_CACHE_TTL_MS = 60_000

/** Factory role granted to AgentOS `ADMIN` principals. */
export const FACTORY_ADMIN_ROLE = 'admin'

/** Factory role granted to AgentOS `MEMBER` principals. */
export const FACTORY_MEMBER_ROLE = 'dev'

/** Factory role granted to service principals (never an admin role). */
export const SERVICE_RUNNER_ROLE = 'service-runner'

/**
 * A directory entry as returned by an AgentOS user/group lookup.
 *
 * Only the fields the Factory needs are modelled; unknown fields are ignored.
 * A `namespaceId` (internal) or `namespaceExternalId` (IdP key) projects onto
 * the Factory `workstreamId`.
 */
export interface AgentOsDirectoryUser {
  principalId?: string | null
  roles?: string[] | null
  groups?: string[] | null
  namespaceId?: string | null
  namespaceExternalId?: string | null
}

/**
 * Minimal injected AgentOS directory client.
 *
 * The resolver owns no transport: implementations may perform HTTP calls,
 * read a local cache, or be a simple in-memory fake. `getDirectoryUser` may
 * return its answer synchronously or as a promise; both are supported.
 */
export interface AgentOsDirectoryClient {
  getDirectoryUser(
    principalId: string
  ): AgentOsDirectoryUser | null | undefined | Promise<AgentOsDirectoryUser | null | undefined>
}

/** Constructor options for `AgentOsMembershipResolver`. */
export interface AgentOsMembershipResolverOptions {
  /** Injected directory client (no network calls are hardcoded here). */
  directoryClient: AgentOsDirectoryClient
  /**
   * Instance organization id. Falls back to `FACTORY_ORGANIZATION_ID`, then to
   * `DEFAULT_AGENTOS_ORGANIZATION_ID`.
   */
  organizationId?: string
  /**
   * Membership cache TTL in milliseconds. Falls back to
   * `FACTORY_MEMBERSHIP_CACHE_TTL_MS`, then to
   * `DEFAULT_AGENTOS_MEMBERSHIP_CACHE_TTL_MS`. A non-positive TTL disables
   * caching.
   */
  cacheTtlMs?: number
  /**
   * Optional workstream id granted to service principals. Defaults to `null`
   * (services have no workstream).
   */
  serviceWorkstreamId?: string | null
}

/** An in-memory cache entry. */
interface MembershipCacheEntry {
  membership: MembershipInfo
  expiresAt: number
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

function pickString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    (typeof value === 'object' || typeof value === 'function') &&
    value !== null &&
    typeof (value as { then?: unknown }).then === 'function'
  )
}

function isOptionalString(value: unknown): boolean {
  return value === undefined || value === null || typeof value === 'string'
}

function isStringArrayOrNullOrUndefined(value: unknown): boolean {
  if (value === undefined || value === null) return true
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string')
}

/**
 * Validate the *shape* of a directory response. Malformed answers are treated
 * as a failed lookup (fail-closed) rather than being partially trusted.
 */
export function isAgentOsDirectoryUser(value: unknown): value is AgentOsDirectoryUser {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const record = value as Record<string, unknown>
  if (!isOptionalString(record['principalId'])) return false
  if (!isStringArrayOrNullOrUndefined(record['roles'])) return false
  if (!isStringArrayOrNullOrUndefined(record['groups'])) return false
  if (!isOptionalString(record['namespaceId'])) return false
  if (!isOptionalString(record['namespaceExternalId'])) return false
  return true
}

/**
 * Map a single AgentOS role/group token to a Factory role.
 *
 *   - `ADMIN` (any casing) -> `admin`
 *   - `MEMBER` / `DEV` / `DEVELOPER` (any casing) -> `dev`
 *   - anything else -> lowercased, trimmed token (case normalization)
 */
export function normalizeAgentOsRole(raw: string): string | null {
  const token = raw.trim().toLowerCase()
  if (!token) return null
  if (token === 'admin' || token === 'administrator') return FACTORY_ADMIN_ROLE
  if (token === 'member' || token === 'dev' || token === 'developer') return FACTORY_MEMBER_ROLE
  return token
}

/**
 * Normalize and de-duplicate AgentOS role/group sources into Factory roles.
 * Order follows first appearance; unrecognized tokens are lowercased.
 */
export function normalizeAgentOsRoles(...sources: Array<readonly string[] | null | undefined>): string[] {
  const roles: string[] = []
  const seen = new Set<string>()
  for (const source of sources) {
    if (!Array.isArray(source)) continue
    for (const entry of source) {
      if (typeof entry !== 'string') continue
      const mapped = normalizeAgentOsRole(entry)
      if (!mapped || seen.has(mapped)) continue
      seen.add(mapped)
      roles.push(mapped)
    }
  }
  return roles
}

function resolveOrganizationFromEnv(): string {
  return pickString(process.env.FACTORY_ORGANIZATION_ID) ?? DEFAULT_AGENTOS_ORGANIZATION_ID
}

function resolveCacheTtl(explicit: number | undefined): number {
  if (typeof explicit === 'number' && Number.isFinite(explicit) && explicit >= 0) return explicit
  const fromEnv = Number.parseFloat(process.env.FACTORY_MEMBERSHIP_CACHE_TTL_MS ?? '')
  if (Number.isFinite(fromEnv) && fromEnv >= 0) return fromEnv
  return DEFAULT_AGENTOS_MEMBERSHIP_CACHE_TTL_MS
}

function cloneMembership(membership: MembershipInfo): MembershipInfo {
  return {
    organizationId: membership.organizationId ?? null,
    workstreamId: membership.workstreamId ?? null,
    squadId: membership.squadId ?? null,
    roles: Array.isArray(membership.roles) ? [...membership.roles] : [],
  }
}

// ---------------------------------------------------------------------------
// Resolver
// ---------------------------------------------------------------------------

/**
 * Resolve Factory memberships from the AgentOS directory.
 *
 * The resolver is safe to share across requests: its cache is keyed by
 * principal id and never leaks mutable state (cached memberships are cloned on
 * read and write). It implements `MembershipResolver`, so it can be injected
 * directly into the HTTP boundary's `identity.membershipResolver`.
 *
 * Integration note: the HTTP boundary calls `resolveMembershipSync`, which
 * rejects thenables and fails closed. A cache hit is answered synchronously, so
 * pre-warming the cache (one async `resolveMembership` or an explicit
 * `primeCache`) is what makes an async directory client usable on the sync
 * request path.
 */
export class AgentOsMembershipResolver implements MembershipResolver {
  readonly #directoryClient: AgentOsDirectoryClient

  readonly #organizationId: string | null

  readonly #cacheTtlMs: number

  readonly #serviceWorkstreamId: string | null

  readonly #cache: Map<string, MembershipCacheEntry>

  constructor(options: AgentOsMembershipResolverOptions) {
    if (!options || typeof options !== 'object' || !options.directoryClient) {
      throw new TypeError('AgentOsMembershipResolver requires a directoryClient')
    }
    if (typeof options.directoryClient.getDirectoryUser !== 'function') {
      throw new TypeError('AgentOsMembershipResolver directoryClient must expose getDirectoryUser()')
    }
    this.#directoryClient = options.directoryClient
    this.#organizationId = pickString(options.organizationId) ?? resolveOrganizationFromEnv()
    this.#cacheTtlMs = resolveCacheTtl(options.cacheTtlMs)
    this.#serviceWorkstreamId = pickString(options.serviceWorkstreamId)
    this.#cache = new Map()
  }

  /**
   * Resolve the membership of a principal, server-side.
   *
   * Returns synchronously when the answer is already cached (or the principal
   * is a service/an absent id); returns a promise when a real directory lookup
   * is required from an async client. Never throws: failures fail closed.
   */
  resolveMembership(
    principalId: string | null,
    principalType: PrincipalType
  ): MembershipInfo | Promise<MembershipInfo> {
    const id = pickString(principalId)
    if (!id) return EMPTY_MEMBERSHIP

    // Service principals are isolated: no directory lookup, no admin, ever.
    if (principalType === 'service') return this.#serviceMembership()

    const cached = this.#readCache(id)
    if (cached) return cached

    let raw: AgentOsDirectoryUser | null | undefined | Promise<AgentOsDirectoryUser | null | undefined>
    try {
      raw = this.#directoryClient.getDirectoryUser(id)
    } catch {
      return EMPTY_MEMBERSHIP
    }

    if (isThenable(raw)) {
      return Promise.resolve(raw)
        .then((resolved) => this.#projectAndCache(id, resolved))
        .catch(() => EMPTY_MEMBERSHIP)
    }

    return this.#projectAndCache(id, raw)
  }

  /**
   * I/O-tolerant synchronous resolution for synchronously-typed call sites.
   *
   * Returns a cached membership or the service default synchronously; if an
   * uncached async lookup is required, it fails closed with
   * `EMPTY_MEMBERSHIP` instead of blocking the request path (mirroring the
   * free `resolveMembershipSync` helper). Pre-warm the cache with
   * `resolveMembership`/`primeCache` to serve async clients synchronously.
   */
  resolveMembershipSync(principalId: string | null, principalType: PrincipalType): MembershipInfo {
    const result = this.resolveMembership(principalId, principalType)
    if (isThenable(result)) return EMPTY_MEMBERSHIP
    return result
  }

  /** Pre-seed the cache for a principal (used to warm an async client). */
  primeCache(principalId: string, membership: MembershipInfo, ttlMs?: number): void {
    const id = pickString(principalId)
    if (!id || !membership || typeof membership !== 'object') return
    const ttl = typeof ttlMs === 'number' ? ttlMs : this.#cacheTtlMs
    this.#writeCache(id, membership, ttl)
  }

  /** Invalidate one principal's cache entry, or the whole cache when omitted. */
  clearCache(principalId?: string): void {
    if (principalId === undefined) {
      this.#cache.clear()
      return
    }
    const id = pickString(principalId)
    if (id) this.#cache.delete(id)
  }

  /** Read a live (non-expired) cached membership, or `null`. */
  getCachedMembership(principalId: string): MembershipInfo | null {
    const id = pickString(principalId)
    if (!id) return null
    return this.#readCache(id)
  }

  /** Number of live cache entries (expired entries are evicted on read). */
  get cacheSize(): number {
    return this.#cache.size
  }

  // --- internals ----------------------------------------------------------

  #serviceMembership(): MembershipInfo {
    return {
      organizationId: this.#organizationId,
      workstreamId: this.#serviceWorkstreamId,
      squadId: null,
      roles: [SERVICE_RUNNER_ROLE],
    }
  }

  #projectAndCache(id: string, raw: unknown): MembershipInfo {
    if (!isAgentOsDirectoryUser(raw)) return EMPTY_MEMBERSHIP
    const membership = this.#project(raw)
    this.#writeCache(id, membership, this.#cacheTtlMs)
    return membership
  }

  #project(user: AgentOsDirectoryUser): MembershipInfo {
    const roles = normalizeAgentOsRoles(user.roles, user.groups)
    const workstreamId = pickString(user.namespaceId) ?? pickString(user.namespaceExternalId)
    return {
      organizationId: this.#organizationId,
      workstreamId,
      squadId: null,
      roles,
    }
  }

  #readCache(principalId: string): MembershipInfo | null {
    const entry = this.#cache.get(principalId)
    if (!entry) return null
    if (Date.now() >= entry.expiresAt) {
      this.#cache.delete(principalId)
      return null
    }
    return cloneMembership(entry.membership)
  }

  #writeCache(principalId: string, membership: MembershipInfo, ttlMs: number): void {
    if (!Number.isFinite(ttlMs) || ttlMs <= 0) return
    this.#cache.set(principalId, {
      membership: cloneMembership(membership),
      expiresAt: Date.now() + ttlMs,
    })
  }
}
