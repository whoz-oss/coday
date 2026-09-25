/**
 * TrustContext domain primitives.
 *
 * The `TrustContext` is the single identity + security descriptor resolved once
 * at the HTTP boundary (see `factory/dashboard/http-utils.mjs`) and propagated
 * down to every handler. This module owns the vocabulary (principal types,
 * authentication methods) and the pure constructors/validators used to build
 * and check that descriptor.
 *
 * Two families of fields coexist:
 *
 *   - Legacy fields (`namespaceId`, `caseId`, `actorId`, `authorityId`,
 *     `runtimeId`, `agentId`, `threadId`, `trustMode`, `loopback`) kept
 *     byte-for-byte backward compatible for existing dashboard routes.
 *   - Enriched identity fields (`principalId`, `principalType`,
 *     `organizationId`, `workstreamId`, `squadId`, `roles`, `scopes`,
 *     `correlationId`, `authenticationMethod`, `serviceIdentityId`).
 *
 * Impersonation / delegation is disabled by default: `impersonatedBy` and
 * `delegationChain` are strictly `null` unless a trusted future implementation
 * explicitly opts in.
 *
 * Domain purity: this module has no I/O dependency.
 */

/** The kinds of principal a TrustContext can describe. */
export const PRINCIPAL_TYPES = Object.freeze(['human', 'service'] as const)

/** A principal is either a natural person or a machine/service identity. */
export type PrincipalType = (typeof PRINCIPAL_TYPES)[number]

/** How the boundary authenticated the principal for this request. */
export const AUTHENTICATION_METHODS = Object.freeze(['jwt', 'proxy-signature', 'loopback-dev', 'anonymous'] as const)

/** Authentication method used to establish the principal identity. */
export type AuthenticationMethod = (typeof AUTHENTICATION_METHODS)[number]

/** Principal id attributed to unattended local loopback development requests. */
export const LOOPBACK_DEV_PRINCIPAL_ID = 'local-dev-user'

/**
 * The identity + security context resolved once at the HTTP boundary.
 *
 * All fields are always present (no `undefined`) so downstream handlers can
 * reason about identity without defensive `??` everywhere.
 */
export interface TrustContext {
  // --- Legacy fields (strict backward compatibility) -----------------------
  namespaceId: string | null
  caseId: string | null
  actorId: string | null
  authorityId: string | null
  runtimeId: string | null
  agentId: string | null
  threadId: string | null
  trustMode: string
  loopback: boolean

  // --- Enriched identity fields --------------------------------------------
  principalId: string | null
  principalType: PrincipalType
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
  scopes: string[]
  correlationId: string | null
  authenticationMethod: AuthenticationMethod
  serviceIdentityId: string | null

  // --- Impersonation / delegation guard (strictly null by default) ---------
  impersonatedBy: string | null
  delegationChain: string[] | null
}

/** True when `value` is a known principal type. */
export function isPrincipalType(value: unknown): value is PrincipalType {
  return typeof value === 'string' && (PRINCIPAL_TYPES as readonly string[]).includes(value)
}

/** True when `value` is a known authentication method. */
export function isAuthenticationMethod(value: unknown): value is AuthenticationMethod {
  return typeof value === 'string' && (AUTHENTICATION_METHODS as readonly string[]).includes(value)
}

/**
 * Build a fully-populated TrustContext with safe defaults (anonymous, no
 * identity) overridden by the supplied partial. Keeps every field present.
 */
export function createBaseTrustContext(overrides: Partial<TrustContext> = {}): TrustContext {
  const base: TrustContext = {
    namespaceId: null,
    caseId: null,
    actorId: null,
    authorityId: null,
    runtimeId: null,
    agentId: null,
    threadId: null,
    trustMode: 'loopback-only',
    loopback: true,

    principalId: null,
    principalType: 'human',
    organizationId: null,
    workstreamId: null,
    squadId: null,
    roles: [],
    scopes: [],
    correlationId: null,
    authenticationMethod: 'anonymous',
    serviceIdentityId: null,

    impersonatedBy: null,
    delegationChain: null,
  }
  return { ...base, ...overrides }
}

/** Build an explicitly anonymous context (unauthenticated remote caller). */
export function createAnonymousTrustContext(overrides: Partial<TrustContext> = {}): TrustContext {
  return createBaseTrustContext({
    ...overrides,
    authenticationMethod: 'anonymous',
    principalId: overrides.principalId ?? null,
    scopes: overrides.scopes ?? [],
  })
}

/** Build an unauthenticated local loopback development context. */
export function createLoopbackDevTrustContext(overrides: Partial<TrustContext> = {}): TrustContext {
  return createBaseTrustContext({
    ...overrides,
    authenticationMethod: 'loopback-dev',
    principalId: overrides.principalId ?? LOOPBACK_DEV_PRINCIPAL_ID,
    scopes: overrides.scopes ?? ['*'],
    loopback: true,
  })
}

const NULLABLE_STRING_FIELDS = [
  'namespaceId',
  'caseId',
  'actorId',
  'authorityId',
  'runtimeId',
  'agentId',
  'threadId',
  'principalId',
  'organizationId',
  'workstreamId',
  'squadId',
  'correlationId',
  'serviceIdentityId',
  'impersonatedBy',
] as const

const STRING_ARRAY_FIELDS = ['roles', 'scopes'] as const

/**
 * Validate the structure of a TrustContext. Pure and never throws: returns a
 * `{ valid, errors }` report so callers can decide how to react.
 */
export function validateTrustContext(context: unknown): { valid: boolean; errors: string[] } {
  const errors: string[] = []
  if (!context || typeof context !== 'object' || Array.isArray(context)) {
    return { valid: false, errors: ['trust-context must be an object'] }
  }
  const record = context as Record<string, unknown>
  for (const field of NULLABLE_STRING_FIELDS) {
    const value = record[field]
    if (value !== null && typeof value !== 'string') {
      errors.push(`${field} must be a string or null`)
    }
  }
  for (const field of STRING_ARRAY_FIELDS) {
    const value = record[field]
    if (!Array.isArray(value) || value.some((entry) => typeof entry !== 'string')) {
      errors.push(`${field} must be an array of strings`)
    }
  }
  if (typeof record.trustMode !== 'string') errors.push('trustMode must be a string')
  if (typeof record.loopback !== 'boolean') errors.push('loopback must be a boolean')
  if (!isPrincipalType(record.principalType)) errors.push('principalType must be human or service')
  if (!isAuthenticationMethod(record.authenticationMethod)) {
    errors.push('authenticationMethod must be jwt, proxy-signature, loopback-dev or anonymous')
  }
  if (record.impersonatedBy !== null) errors.push('impersonatedBy must be null (impersonation is disabled by default)')
  if (record.delegationChain !== null)
    errors.push('delegationChain must be null (impersonation is disabled by default)')
  return { valid: errors.length === 0, errors }
}
