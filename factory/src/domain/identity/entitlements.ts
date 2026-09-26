/**
 * Entitlement resolution (Milestone B, wave B6, task B6-T3).
 *
 * The Factory authorizes privileged operations (artifact purge / legal hold /
 * GC, plus any future admin command) from the *verified* `TrustContext` alone.
 * This module is the pure decision function behind the HTTP boundary's
 * `checkAdminAuthorization` seam: it interprets the roles and scopes that the
 * server-side membership resolver (typically `AgentOsMembershipResolver`)
 * attached to the context.
 *
 * AgentOS vocabulary → Factory entitlements:
 *   - directory role `ADMIN`  → Factory role `admin` (privileged);
 *   - directory role `MEMBER` → Factory role `dev`   (unprivileged).
 *   Role tokens are normalized through `normalizeAgentOsRole`, so a raw
 *   `ADMIN`/`Member` token is interpreted exactly like the resolver's output.
 *
 * Entitlement rules (fail-closed):
 *   - `principalId` is the identity anchor (email / stable principal id).
 *   - `admin` role, the explicit `admin:*` scope or the loopback-dev wildcard
 *     `*` scope grant admin. Nothing else does.
 *   - An unauthenticated (`anonymous`) context has zero privilege, even if it
 *     carries roles/scopes.
 *   - A namespace-scoped admin is only admin *of their own* organization +
 *     workstream: {@link authorizeAdminAccess} rejects a target scope that does
 *     not match the verified membership (`OUT_OF_NAMESPACE`).
 *   - Client headers (`x-roles`, `x-organization-id`, `x-workstream-id`, ...)
 *     are never consulted here.
 *
 * Domain purity: this module has no I/O dependency.
 */

// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import { FACTORY_ADMIN_ROLE, FACTORY_MEMBER_ROLE, normalizeAgentOsRole } from './agentos-membership-resolver.ts'
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import { isAuthenticationMethod, type PrincipalType } from './trust-context.ts'

/** Explicit admin scope granted by a privileged credential. */
export const ADMIN_SCOPE = 'admin:*'

/** Loopback-dev wildcard scope (`extractTrustContext` only attributes it locally). */
export const ADMIN_WILDCARD_SCOPE = '*'

/** Stable, machine-readable reasons an admin decision can be refused. */
export const ADMIN_AUTHORIZATION_REASONS = Object.freeze({
  MISSING_TRUST_CONTEXT: 'MISSING_TRUST_CONTEXT',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  INSUFFICIENT_ADMIN_PERMISSIONS: 'INSUFFICIENT_ADMIN_PERMISSIONS',
  OUT_OF_NAMESPACE: 'OUT_OF_NAMESPACE',
} as const)

/** One of the {@link ADMIN_AUTHORIZATION_REASONS} values. */
export type AdminAuthorizationReason = (typeof ADMIN_AUTHORIZATION_REASONS)[keyof typeof ADMIN_AUTHORIZATION_REASONS]

/** Normalized entitlements of a principal, or `null` for a missing context. */
export interface PrincipalEntitlements {
  principalId: string | null
  principalType: PrincipalType
  organizationId: string | null
  workstreamId: string | null
  roles: string[]
  scopes: string[]
  /** `false` only for an explicit `authenticationMethod: 'anonymous'`. */
  authenticated: boolean
  /** `true` when the principal may run privileged (admin) commands. */
  isAdmin: boolean
}

/** Structured admin decision, mirroring the historical guard contract. */
export interface AdminAuthorizationDecision {
  authorized: boolean
  reason: AdminAuthorizationReason | null
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function pickId(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

/** Normalize a role list through the AgentOS vocabulary, de-duplicated. */
export function normalizeEntitlementRoles(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  const roles: string[] = []
  const seen = new Set<string>()
  for (const raw of input) {
    if (typeof raw !== 'string') continue
    const mapped = normalizeAgentOsRole(raw)
    if (!mapped || seen.has(mapped)) continue
    seen.add(mapped)
    roles.push(mapped)
  }
  return roles
}

/** Normalize a scope list to trimmed, de-duplicated non-empty strings. */
export function normalizeEntitlementScopes(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  const scopes: string[] = []
  const seen = new Set<string>()
  for (const raw of input) {
    if (typeof raw !== 'string') continue
    const trimmed = raw.trim()
    if (!trimmed || seen.has(trimmed)) continue
    seen.add(trimmed)
    scopes.push(trimmed)
  }
  return scopes
}

/** True when an AgentOS/Factory role token maps to the Factory `admin` role. */
export function isAdminRole(role: unknown): boolean {
  return typeof role === 'string' && normalizeAgentOsRole(role) === FACTORY_ADMIN_ROLE
}

/** True when an AgentOS/Factory role token maps to the Factory `dev` role. */
export function isMemberRole(role: unknown): boolean {
  return typeof role === 'string' && normalizeAgentOsRole(role) === FACTORY_MEMBER_ROLE
}

/**
 * Resolve the normalized entitlements of a `TrustContext`, fail-closed.
 *
 * Returns `null` when no context object is supplied. Never throws. An explicit
 * `anonymous` context resolves to `{ authenticated: false, isAdmin: false }`.
 */
export function resolvePrincipalEntitlements(trustContext: unknown): PrincipalEntitlements | null {
  if (!isPlainObject(trustContext)) return null
  const authenticated = !(
    isAuthenticationMethod(trustContext.authenticationMethod) && trustContext.authenticationMethod === 'anonymous'
  )
  const roles = normalizeEntitlementRoles(trustContext.roles)
  const scopes = normalizeEntitlementScopes(trustContext.scopes)
  const isAdmin =
    authenticated &&
    (roles.includes(FACTORY_ADMIN_ROLE) || scopes.includes(ADMIN_SCOPE) || scopes.includes(ADMIN_WILDCARD_SCOPE))
  return {
    principalId: pickId(trustContext.principalId),
    principalType: trustContext.principalType === 'service' ? 'service' : 'human',
    organizationId: pickId(trustContext.organizationId),
    workstreamId: pickId(trustContext.workstreamId),
    roles,
    scopes,
    authenticated,
    isAdmin,
  }
}

/** True when the principal carries an admin entitlement (fail-closed). */
export function hasAdminEntitlement(trustContext: unknown): boolean {
  return resolvePrincipalEntitlements(trustContext)?.isAdmin ?? false
}

/**
 * Decide whether a principal may run a privileged command, optionally scoped to
 * a target organization/workstream.
 *
 * The signature is intentionally `(trustContext, target?)`: the historical
 * `checkAdminAuthorization(trustContext)` call sites keep working unchanged,
 * while a namespace-scoped resource can pass its own
 * `{ organizationId, workstreamId }` as `target` to get a strong namespace
 * check. A target id that is absent (or `null`) is not checked; a present target
 * id must match the verified membership exactly.
 */
export function authorizeAdminAccess(trustContext: unknown, target?: unknown): AdminAuthorizationDecision {
  const entitlements = resolvePrincipalEntitlements(trustContext)
  if (!entitlements) return { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.MISSING_TRUST_CONTEXT }
  if (!entitlements.authenticated) return { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.UNAUTHENTICATED }
  if (!entitlements.isAdmin) {
    return { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.INSUFFICIENT_ADMIN_PERMISSIONS }
  }
  if (isPlainObject(target)) {
    const targetOrganizationId = pickId(target.organizationId)
    const targetWorkstreamId = pickId(target.workstreamId)
    if (targetOrganizationId && entitlements.organizationId !== targetOrganizationId) {
      return { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.OUT_OF_NAMESPACE }
    }
    if (targetWorkstreamId && entitlements.workstreamId !== targetWorkstreamId) {
      return { authorized: false, reason: ADMIN_AUTHORIZATION_REASONS.OUT_OF_NAMESPACE }
    }
  }
  return { authorized: true, reason: null }
}

/** Alias emphasizing the namespace-scoped intent at a call site. */
export const authorizeNamespaceAdminAccess = authorizeAdminAccess
