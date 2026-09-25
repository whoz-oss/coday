/**
 * Server-side membership resolution.
 *
 * Memberships (`organizationId`, `workstreamId`, `squadId`, `roles`) describe
 * *what a principal is entitled to*. They must never be taken from unsigned
 * client headers: the boundary resolves them server-side, from the principal
 * established by the Fake IdP, using a `MembershipResolver`.
 *
 * `LocalDevMembershipResolver` / `MockMembershipResolver` provide an in-memory
 * directory for local development and tests; a real implementation would query
 * the authoritative organization directory.
 *
 * Domain purity: no I/O dependency.
 */

// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import type { PrincipalType } from './trust-context.ts'

/** The membership/entitlement data attached to a resolved principal. */
export interface MembershipInfo {
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
}

/** A principal with no known membership (fail-closed default). */
export const EMPTY_MEMBERSHIP: MembershipInfo = Object.freeze({
  organizationId: null,
  workstreamId: null,
  squadId: null,
  roles: [],
})

/** Resolves memberships for an authenticated principal, server-side. */
export interface MembershipResolver {
  resolveMembership(principalId: string | null, principalType: PrincipalType): MembershipInfo | Promise<MembershipInfo>
}

function isMembershipInfo(value: unknown): value is MembershipInfo {
  return !!value && typeof value === 'object'
}

function isThenable(value: unknown): boolean {
  return (
    !!value &&
    (typeof value === 'object' || typeof value === 'function') &&
    typeof (value as { then?: unknown }).then === 'function'
  )
}

/** Default membership granted when a principal has no explicit entry. */
export function defaultMembershipFor(principalType: PrincipalType): MembershipInfo {
  return {
    organizationId: 'org-local-dev',
    workstreamId: 'ws-default',
    squadId: null,
    roles: principalType === 'service' ? ['service-runner'] : ['developer'],
  }
}

/**
 * In-memory membership directory used for local development and tests.
 *
 * Resolution is deterministic: an explicit entry wins, otherwise a synthetic
 * default membership is granted based on the principal type. An absent
 * principal resolves to no membership at all.
 */
export class LocalDevMembershipResolver implements MembershipResolver {
  #directory: Map<string, MembershipInfo>

  #defaults: boolean

  constructor(initialData?: Record<string, MembershipInfo>, useDefaults = true) {
    this.#directory = new Map()
    this.#defaults = useDefaults
    if (initialData) {
      for (const [principalId, membership] of Object.entries(initialData)) {
        this.setMembership(principalId, membership)
      }
    }
  }

  /** Register or replace the membership of a principal. */
  setMembership(principalId: string, membership: MembershipInfo): void {
    this.#directory.set(principalId, {
      organizationId: membership.organizationId ?? null,
      workstreamId: membership.workstreamId ?? null,
      squadId: membership.squadId ?? null,
      roles: Array.isArray(membership.roles) ? [...membership.roles] : [],
    })
  }

  /** Remove a principal from the directory. */
  clearMembership(principalId: string): void {
    this.#directory.delete(principalId)
  }

  resolveMembership(principalId: string | null, principalType: PrincipalType): MembershipInfo {
    if (!principalId) return { ...EMPTY_MEMBERSHIP, roles: [] }
    const known = this.#directory.get(principalId)
    if (known) return { ...known, roles: [...known.roles] }
    return this.#defaults ? defaultMembershipFor(principalType) : { ...EMPTY_MEMBERSHIP, roles: [] }
  }
}

/**
 * Alias kept for the milestone vocabulary: a mock resolver with an explicit
 * in-memory directory. Behaves exactly like `LocalDevMembershipResolver`.
 */
export class MockMembershipResolver extends LocalDevMembershipResolver {}

/**
 * Synchronously resolve memberships at the HTTP boundary.
 *
 * The boundary is synchronous and supports the concrete resolvers directly. A
 * resolver returning a promise cannot be awaited here, so it is treated as
 * unresolved (fail-closed: no membership, no privilege) rather than blocking
 * or throwing on the request path.
 */
export function resolveMembershipSync(
  resolver: MembershipResolver | null | undefined,
  principalId: string | null,
  principalType: PrincipalType
): MembershipInfo {
  if (!resolver || typeof resolver.resolveMembership !== 'function') {
    return { ...EMPTY_MEMBERSHIP, roles: [] }
  }
  const result = resolver.resolveMembership(principalId, principalType)
  if (isThenable(result) || !isMembershipInfo(result)) {
    return { ...EMPTY_MEMBERSHIP, roles: [] }
  }
  return {
    organizationId: typeof result.organizationId === 'string' ? result.organizationId : null,
    workstreamId: typeof result.workstreamId === 'string' ? result.workstreamId : null,
    squadId: typeof result.squadId === 'string' ? result.squadId : null,
    roles: Array.isArray(result.roles) ? result.roles.filter((role) => typeof role === 'string') : [],
  }
}
