/**
 * Fake Identity Provider for local development and tests.
 *
 * This is deliberately a *fake* IdP: it lets the Factory HTTP boundary be
 * exercised end-to-end (JWT OIDC-shaped tokens and proxy-signed headers)
 * without any external identity provider. It relies only on `node:crypto`
 * (HMAC-SHA256), so it has zero third-party dependency and stays fully
 * offline.
 *
 * Security notes:
 *   - Every accepted credential is a *signature* over the claimed identity.
 *     A header supplied without a valid signature MUST be ignored by the
 *     boundary (see `factory/dashboard/http-utils.mjs`).
 *   - Proxy signatures carry a timestamp; stale signatures are rejected to
 *     blunt replay.
 *   - Only the principal identity is signed. Memberships (`organizationId`,
 *     `workstreamId`, `squadId`, `roles`) are never carried in these headers:
 *     they are resolved server-side by a `MembershipResolver`.
 *
 * This module is intentionally the runtime implementation used by both the
 * dashboard boundary and the tests. Types live alongside the code.
 */

import { createHmac, timingSafeEqual } from 'node:crypto'

// Node resolves this specifier at runtime (native TS type stripping requires an
// explicit `.ts` extension); the factory tsconfig predates
// `allowImportingTsExtensions`, so the extension is suppressed here.
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import type { PrincipalType } from './trust-context.ts'

/** Secret used when none is configured (local dev only). */
export const DEFAULT_FAKE_IDP_SECRET = 'coday-fake-idp-dev-secret'

/** Issuer claim of tokens minted by this fake IdP. */
export const FAKE_IDP_ISSUER = 'coday-fake-idp'

/** Default audience when a caller does not specify one. */
export const DEFAULT_FAKE_IDP_AUDIENCE = 'coday-factory'

/** Default accepted age of a signed proxy header (replay window). */
export const DEFAULT_PROXY_SIGNATURE_TTL_MS = 5 * 60 * 1000

/** Default JWT lifetime, in seconds. */
export const DEFAULT_JWT_TTL_SECONDS = 300

// --- Proxy header names -----------------------------------------------------

export const PROXY_SIGNATURE_HEADER = 'x-proxy-signature'
export const PROXY_TIMESTAMP_HEADER = 'x-proxy-timestamp'
export const PROXY_PRINCIPAL_ID_HEADER = 'x-proxy-principal-id'
export const PROXY_PRINCIPAL_TYPE_HEADER = 'x-proxy-principal-type'
export const PROXY_SERVICE_IDENTITY_ID_HEADER = 'x-proxy-service-identity-id'
export const PROXY_SCOPES_HEADER = 'x-proxy-scopes'

/** Header names covered by the proxy signature, in canonical order. */
export const SIGNED_PROXY_HEADERS = Object.freeze([
  PROXY_PRINCIPAL_ID_HEADER,
  PROXY_PRINCIPAL_TYPE_HEADER,
  PROXY_SERVICE_IDENTITY_ID_HEADER,
  PROXY_SCOPES_HEADER,
  PROXY_TIMESTAMP_HEADER,
] as const)

// --- Shared helpers ---------------------------------------------------------

function normalizeSecret(secret?: string | null): string {
  return typeof secret === 'string' && secret.length > 0 ? secret : DEFAULT_FAKE_IDP_SECRET
}

function encodeBase64url(value: string): string {
  return Buffer.from(value, 'utf8').toString('base64url')
}

function decodeBase64url(value: string): string {
  return Buffer.from(value, 'base64url').toString('utf8')
}

function hmac(data: string, secret: string): string {
  return createHmac('sha256', secret).update(data).digest('base64url')
}

function safeEqual(left: string, right: string): boolean {
  const leftBuffer = Buffer.from(left, 'utf8')
  const rightBuffer = Buffer.from(right, 'utf8')
  if (leftBuffer.length !== rightBuffer.length) return false
  return timingSafeEqual(leftBuffer, rightBuffer)
}

function toHeaderRecord(headers: unknown): Record<string, string> {
  const record: Record<string, string> = {}
  if (!headers || typeof headers !== 'object') return record
  for (const [rawName, rawValue] of Object.entries(headers as Record<string, unknown>)) {
    if (rawValue === undefined || rawValue === null) continue
    const value = Array.isArray(rawValue) ? rawValue[0] : rawValue
    if (value === undefined || value === null) continue
    record[rawName.toLowerCase()] = String(value)
  }
  return record
}

function parseScopes(value: string | undefined): string[] {
  if (typeof value !== 'string' || value.length === 0) return []
  return value
    .split(',')
    .map((scope) => scope.trim())
    .filter((scope) => scope.length > 0)
}

/** Deterministic payload signed by the proxy: sorted `name:value` lines. */
function canonicalProxyPayload(headers: Record<string, string>): string {
  return SIGNED_PROXY_HEADERS.filter((name) => headers[name] !== undefined)
    .map((name) => `${name}:${headers[name]}`)
    .join('\n')
}

// --- JWT --------------------------------------------------------------------

/** Claims a caller may supply when minting a token. */
export interface FakeJwtPayload {
  principalId?: string | null
  principalType?: PrincipalType
  audience?: string
  scopes?: string[]
  serviceIdentityId?: string | null
  [claim: string]: unknown
}

/** Decoded, verified JWT claims. */
export interface FakeJwtClaims {
  iss: string
  aud: string
  iat: number
  exp: number
  sub?: string
  principalId?: string
  principalType?: PrincipalType
  scopes?: string[]
  serviceIdentityId?: string
  [claim: string]: unknown
}

/** Options accepted by `verifyJwt`. */
export interface VerifyJwtOptions {
  audience?: string
  clockToleranceSeconds?: number
  nowSeconds?: number
}

/** Outcome of a JWT verification. */
export interface JwtVerificationResult {
  valid: boolean
  claims?: FakeJwtClaims
  reason?: string
}

/**
 * Mint a compact HS256 JWT (`header.payload.signature`).
 *
 * A negative `expiresInSeconds` yields an already-expired token, which is the
 * idiomatic way to exercise the rejection path in tests.
 */
export function issueJwt(
  payload: FakeJwtPayload = {},
  secret: string = DEFAULT_FAKE_IDP_SECRET,
  expiresInSeconds: number = DEFAULT_JWT_TTL_SECONDS
): string {
  const resolvedSecret = normalizeSecret(secret)
  const nowSeconds = Math.floor(Date.now() / 1000)
  const { audience, principalId, ...rest } = payload
  const claims: Record<string, unknown> = {
    iss: FAKE_IDP_ISSUER,
    aud: audience ?? DEFAULT_FAKE_IDP_AUDIENCE,
    iat: nowSeconds,
    exp: nowSeconds + Math.floor(expiresInSeconds),
    ...rest,
  }
  if (principalId !== undefined && principalId !== null) {
    claims.sub = principalId
    claims.principalId = principalId
  }
  const encodedHeader = encodeBase64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const encodedPayload = encodeBase64url(JSON.stringify(claims))
  const signature = hmac(`${encodedHeader}.${encodedPayload}`, resolvedSecret)
  return `${encodedHeader}.${encodedPayload}.${signature}`
}

/** Options accepted by `issueServiceToken`. */
export interface ServiceTokenOptions {
  serviceIdentityId: string
  audience?: string
  scopes?: string[]
  principalId?: string
  expiresInSeconds?: number
  secret?: string
  [claim: string]: unknown
}

/**
 * Mint a short-lived service credential: a JWT with `principalType: 'service'`,
 * an explicit `audience`, explicit `scopes` and a `serviceIdentityId`.
 */
export function issueServiceToken(
  options: ServiceTokenOptions,
  secret: string = DEFAULT_FAKE_IDP_SECRET,
  expiresInSeconds: number = DEFAULT_JWT_TTL_SECONDS
): string {
  const {
    serviceIdentityId,
    audience,
    scopes,
    principalId,
    expiresInSeconds: perTokenTtl,
    secret: perTokenSecret,
    ...extra
  } = options
  const payload: FakeJwtPayload = {
    ...extra,
    principalId: principalId ?? serviceIdentityId,
    principalType: 'service',
    serviceIdentityId,
    scopes: scopes ?? [],
  }
  if (audience !== undefined) payload.audience = audience
  return issueJwt(payload, perTokenSecret ?? secret, perTokenTtl ?? expiresInSeconds)
}

/** Verify a compact JWT: structure, HMAC signature, expiry, not-before, audience. */
export function verifyJwt(
  token: unknown,
  secret: string = DEFAULT_FAKE_IDP_SECRET,
  options: VerifyJwtOptions = {}
): JwtVerificationResult {
  if (typeof token !== 'string' || token.length === 0) return { valid: false, reason: 'missing-token' }
  const parts = token.split('.')
  if (parts.length !== 3) return { valid: false, reason: 'malformed-token' }
  const [encodedHeader, encodedPayload, signature] = parts
  if (encodedHeader === undefined || encodedPayload === undefined || signature === undefined) {
    return { valid: false, reason: 'malformed-token' }
  }
  const expected = hmac(`${encodedHeader}.${encodedPayload}`, normalizeSecret(secret))
  if (!safeEqual(signature, expected)) return { valid: false, reason: 'invalid-signature' }

  let claims: FakeJwtClaims
  try {
    claims = JSON.parse(decodeBase64url(encodedPayload)) as FakeJwtClaims
  } catch {
    return { valid: false, reason: 'unparseable-payload' }
  }
  if (!claims || typeof claims !== 'object') return { valid: false, reason: 'unparseable-payload' }

  const nowSeconds = options.nowSeconds ?? Math.floor(Date.now() / 1000)
  const tolerance = options.clockToleranceSeconds ?? 0
  if (typeof claims.exp === 'number' && nowSeconds > claims.exp + tolerance) {
    return { valid: false, reason: 'expired' }
  }
  if (typeof claims.nbf === 'number' && nowSeconds + tolerance < claims.nbf) {
    return { valid: false, reason: 'not-yet-valid' }
  }
  if (options.audience !== undefined && claims.aud !== options.audience) {
    return { valid: false, reason: 'invalid-audience' }
  }
  return { valid: true, claims }
}

// --- Signed proxy headers ---------------------------------------------------

/** Identity claims carried by signed proxy headers. */
export interface ProxyIdentityClaims {
  principalId: string
  principalType: PrincipalType
  serviceIdentityId?: string | null
  scopes?: string[]
}

/** Options accepted by `signProxyHeaders`. */
export interface SignProxyHeadersOptions {
  timestamp?: number
}

/** Options accepted by `verifyProxyHeaders`. */
export interface VerifyProxyHeadersOptions {
  now?: number
  ttlMs?: number
}

/** Outcome of a proxy header verification. */
export interface ProxyVerificationResult {
  valid: boolean
  claims?: {
    principalId: string
    principalType: PrincipalType
    serviceIdentityId: string | null
    scopes: string[]
  }
  reason?: string
}

/**
 * Sign a set of identity claims as proxy headers. Returns a flat object of
 * lowercase header names ready to be placed on an outbound request.
 */
export function signProxyHeaders(
  claims: ProxyIdentityClaims,
  secret: string = DEFAULT_FAKE_IDP_SECRET,
  options: SignProxyHeadersOptions = {}
): Record<string, string> {
  const timestamp = options.timestamp ?? Date.now()
  const signed: Record<string, string> = {
    [PROXY_PRINCIPAL_ID_HEADER]: String(claims.principalId),
    [PROXY_PRINCIPAL_TYPE_HEADER]: String(claims.principalType),
    [PROXY_TIMESTAMP_HEADER]: String(timestamp),
  }
  if (claims.serviceIdentityId) signed[PROXY_SERVICE_IDENTITY_ID_HEADER] = String(claims.serviceIdentityId)
  if (Array.isArray(claims.scopes)) signed[PROXY_SCOPES_HEADER] = claims.scopes.join(',')
  const signature = hmac(canonicalProxyPayload(signed), normalizeSecret(secret))
  return { ...signed, [PROXY_SIGNATURE_HEADER]: signature }
}

/** True when the request carries a proxy signature header (valid or not). */
export function hasProxySignature(headers: unknown): boolean {
  return toHeaderRecord(headers)[PROXY_SIGNATURE_HEADER] !== undefined
}

/**
 * Verify signed proxy headers: presence, timestamp freshness (replay window)
 * and HMAC signature. Invalid signatures are rejected — the boundary must then
 * ignore every identity header rather than trust it.
 */
export function verifyProxyHeaders(
  headers: unknown,
  secret: string = DEFAULT_FAKE_IDP_SECRET,
  options: VerifyProxyHeadersOptions = {}
): ProxyVerificationResult {
  const normalized = toHeaderRecord(headers)
  const signature = normalized[PROXY_SIGNATURE_HEADER]
  if (!signature) return { valid: false, reason: 'missing-signature' }

  const timestampRaw = normalized[PROXY_TIMESTAMP_HEADER]
  if (!timestampRaw) return { valid: false, reason: 'missing-timestamp' }
  const timestamp = Number(timestampRaw)
  if (!Number.isFinite(timestamp)) return { valid: false, reason: 'invalid-timestamp' }

  const now = options.now ?? Date.now()
  const ttlMs = options.ttlMs ?? DEFAULT_PROXY_SIGNATURE_TTL_MS
  if (Math.abs(now - timestamp) > ttlMs) return { valid: false, reason: 'stale-timestamp' }

  const principalId = normalized[PROXY_PRINCIPAL_ID_HEADER]
  const principalType = normalized[PROXY_PRINCIPAL_TYPE_HEADER]
  if (!principalId) return { valid: false, reason: 'missing-principal-id' }
  if (!principalType) return { valid: false, reason: 'missing-principal-type' }

  const signed: Record<string, string> = {}
  for (const name of SIGNED_PROXY_HEADERS) {
    const value = normalized[name]
    if (value !== undefined) signed[name] = value
  }
  const expected = hmac(canonicalProxyPayload(signed), normalizeSecret(secret))
  if (!safeEqual(signature, expected)) return { valid: false, reason: 'invalid-signature' }

  return {
    valid: true,
    claims: {
      principalId,
      principalType: principalType === 'service' ? 'service' : 'human',
      serviceIdentityId: normalized[PROXY_SERVICE_IDENTITY_ID_HEADER] ?? null,
      scopes: parseScopes(normalized[PROXY_SCOPES_HEADER]),
    },
  }
}
