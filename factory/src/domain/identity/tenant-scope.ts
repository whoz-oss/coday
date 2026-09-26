/**
 * Tenant scope domain primitives (Milestone B, wave B6, task B6-T3).
 *
 * The Factory persists every aggregate in a composite tenant scope
 * (`organizationId` + `workstreamId`). This module owns the *only* sanctioned
 * way to turn a resolved {@link TrustContext} into that scope, so no route, use
 * case or repository wiring has to re-implement the (security-sensitive) rule.
 *
 * Invariants (fail-closed):
 *   - The scope is derived from the *verified* `TrustContext` produced at the
 *     HTTP boundary, never from client headers and never from an implicit
 *     default.
 *   - An unauthenticated (`anonymous`) caller has no tenant scope at all.
 *   - A missing/blank `organizationId` or `workstreamId` yields no scope.
 *   - Any uncertainty (malformed context, blank id) resolves to `null` scope,
 *     never to a permissive default.
 *
 * Domain purity: this module has no I/O dependency.
 */

// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import { isAuthenticationMethod } from './trust-context.ts'

/** The composite tenant identity every command/query must be scoped by. */
export interface TenantScope {
  organizationId: string
  workstreamId: string
}

/** Stable, machine-readable reasons a tenant scope could not be resolved. */
export const TENANT_SCOPE_REASONS = Object.freeze({
  MISSING_TRUST_CONTEXT: 'MISSING_TRUST_CONTEXT',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  MISSING_ORGANIZATION_ID: 'MISSING_ORGANIZATION_ID',
  MISSING_WORKSTREAM_ID: 'MISSING_WORKSTREAM_ID',
} as const)

/** One of the {@link TENANT_SCOPE_REASONS} values. */
export type TenantScopeReason = (typeof TENANT_SCOPE_REASONS)[keyof typeof TENANT_SCOPE_REASONS]

/** Result of resolving a tenant scope, mirroring the admin-guard decision shape. */
export interface TenantScopeDecision {
  scope: TenantScope | null
  reason: TenantScopeReason | null
}

/** Raised by {@link requireTenantScope} when no tenant scope can be resolved. */
export class TenantScopeError extends Error {
  /** Stable error code for transport mapping (`403 TENANT_SCOPE_REQUIRED`). */
  readonly code = 'TENANT_SCOPE_REQUIRED'

  /** Precise fail-closed reason, safe to log. */
  readonly reason: TenantScopeReason

  constructor(reason: TenantScopeReason, message?: string) {
    super(message ?? `Tenant scope required (${reason})`)
    this.name = 'TenantScopeError'
    this.reason = reason
  }
}

function pickScopeId(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

/**
 * Resolve the tenant scope of a verified `TrustContext`, fail-closed.
 *
 * Returns `{ scope, reason: null }` only when the context is authenticated and
 * carries a non-blank `organizationId` and `workstreamId`. Every other case
 * returns a `null` scope plus the precise reason. An explicit
 * `authenticationMethod: 'anonymous'` is refused; an absent
 * `authenticationMethod` is tolerated for transport-agnostic call sites.
 */
export function resolveTenantScope(trustContext: unknown): TenantScopeDecision {
  if (!trustContext || typeof trustContext !== 'object' || Array.isArray(trustContext)) {
    return { scope: null, reason: TENANT_SCOPE_REASONS.MISSING_TRUST_CONTEXT }
  }
  const record = trustContext as Record<string, unknown>
  if (isAuthenticationMethod(record.authenticationMethod) && record.authenticationMethod === 'anonymous') {
    return { scope: null, reason: TENANT_SCOPE_REASONS.UNAUTHENTICATED }
  }
  const organizationId = pickScopeId(record.organizationId)
  if (!organizationId) return { scope: null, reason: TENANT_SCOPE_REASONS.MISSING_ORGANIZATION_ID }
  const workstreamId = pickScopeId(record.workstreamId)
  if (!workstreamId) return { scope: null, reason: TENANT_SCOPE_REASONS.MISSING_WORKSTREAM_ID }
  return { scope: { organizationId, workstreamId }, reason: null }
}

/** Convenience wrapper returning just the scope (or `null`). */
export function tenantScopeOf(trustContext: unknown): TenantScope | null {
  return resolveTenantScope(trustContext).scope
}

/**
 * Enforce {@link resolveTenantScope}, throwing {@link TenantScopeError} when the
 * caller has no verified tenant scope. Use this at the top of a use case before
 * any tenant-scoped read or write.
 */
export function requireTenantScope(trustContext: unknown): TenantScope {
  const decision = resolveTenantScope(trustContext)
  if (!decision.scope) {
    throw new TenantScopeError(decision.reason ?? TENANT_SCOPE_REASONS.MISSING_TRUST_CONTEXT)
  }
  return decision.scope
}

/** Stable, collision-free key for a tenant scope (useful for maps/caches). */
export function tenantScopeKey(scope: TenantScope): string {
  return `${scope.organizationId}\u0000${scope.workstreamId}`
}

/** True when two scopes designate the same organization *and* workstream. */
export function sameTenantScope(left: TenantScope | null | undefined, right: TenantScope | null | undefined): boolean {
  if (!left || !right) return false
  return left.organizationId === right.organizationId && left.workstreamId === right.workstreamId
}

/**
 * True when `scope` matches the requested `target` tenant exactly.
 *
 * A scope never matches across a different organization or workstream, so a
 * cross-workstream (or cross-organization) request is always rejected.
 */
export function isTenantScopeWithin(scope: TenantScope | null | undefined, target: TenantScope): boolean {
  return sameTenantScope(scope, target)
}
