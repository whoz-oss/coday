/**
 * Coday identity bridge (Milestone B, wave B6, task B6-T2a).
 *
 * Context
 * -------
 * Coday is fronted by a proxy that resolves the *user identity* (an email
 * address) before forwarding the request to the Factory. The Factory HTTP
 * boundary (`factory/dashboard/http-utils.mjs`) never trusts raw client
 * headers: it only accepts a principal coming from a *verified* credential,
 * either an `Authorization: Bearer <jwt>` token or freshly signed proxy
 * headers. This module is the missing half of that contract — it turns the
 * Coday-resolved identity into such a verifiable credential.
 *
 * What this bridge does
 * ---------------------
 * `mintCodayIdentityToken` mints a compact HS256 JWT (via the local Fake IdP
 * `issueJwt`, same shared secret as the Factory) whose claims are:
 *
 *   - `principalId` / `sub` = the trimmed Coday email (stable at Whoz);
 *   - `principalType`       = `'human'` by default, or `'service'`;
 *   - `scopes`              = the delegated scopes (defaults to `[]`);
 *   - `aud`                 = `DEFAULT_FAKE_IDP_AUDIENCE` unless overridden;
 *   - `iss`, `iat`, `exp`   = standard claims.
 *
 * The token is then verified, unchanged, by `extractTrustContext`, which sets
 * `authenticationMethod: 'jwt'` and populates `principalId`, `principalType`,
 * `scopes` and `serviceIdentityId`. `extractTrustContext` MUST NOT be modified.
 *
 * Security invariants
 * -------------------
 *   1. `principalId = email`. The email is the identity anchor (stable at
 *      Whoz); it is trimmed but never case-folded, so the minted principal id
 *      is byte-identical to the resolved Coday email.
 *   2. Fail-closed: any missing / blank / malformed input (email,
 *      `principalType`, `scopes`, `audience`, `expiresInSeconds`, explicit
 *      secret) aborts minting. A caller that cannot prove a valid identity
 *      gets no token — never a partially-trusted one. See
 *      `tryMintCodayIdentityToken` for the non-throwing variant.
 *   3. The `email` argument is the *only* identity source. This module NEVER
 *      reads request headers or any unsigned client input; callers must pass
 *      an email resolved by the trusted proxy/auth layer. Unsigned headers are
 *      not a credential and must never be consulted for authorization.
 *   4. The signature is an HMAC over the token, so tampering, a wrong secret,
 *      or an expired token are all rejected by `verifyJwt`/`extractTrustContext`.
 *
 * Migration path to a real IdP (OIDC / JWKS)
 * ------------------------------------------
 * This Fake IdP is a deliberate development / integration shim, not a
 * production identity provider. Migrating to a real OIDC provider (Auth0,
 * Keycloak, Entra ID, ...) is planned and this module is the single seam to
 * change:
 *
 *   - Instead of *minting* an HS256 token with a shared secret, the bridge
 *     would return the OIDC-issued token (or an exchange result) obtained from
 *     the provider's token endpoint, carrying the same `principalId = email`,
 *     `principalType` and `scopes` vocabulary.
 *   - On the verification side, `extractTrustContext` would stop checking the
 *     HMAC secret and instead validate the asymmetric signature (RS256/ES256)
 *     against the provider's JWKS public keys, plus `iss`/`aud`/`exp`.
 *   - The shared-secret `fakeIdpSecret` wiring would be replaced by an OIDC
 *     issuer + audience configuration; the TrustContext shape stays identical,
 *     which is why the rest of the Factory does not need to change.
 *
 * Until that migration, keep the secret out of source control in production:
 * configure a strong per-instance secret instead of relying on
 * `DEFAULT_FAKE_IDP_SECRET` (which exists for local dev and tests only).
 *
 * Domain purity: this module has no I/O dependency (HMAC via `node:crypto`).
 */

// Node resolves these specifiers at runtime (native TS type stripping requires
// an explicit `.ts` extension); the factory tsconfig predates
// `allowImportingTsExtensions`, so the extensions are suppressed here.
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import { DEFAULT_FAKE_IDP_SECRET, issueJwt } from './fake-idp.ts'
// @ts-ignore -- explicit `.ts` extension needed for Node type stripping
import type { PrincipalType } from './trust-context.ts'

/** The principal kinds a Coday identity may be bridged to. */
export const CODAY_PRINCIPAL_TYPES = Object.freeze(['human', 'service'] as const)

/** Default principal type attributed to a bridged Coday identity. */
export const DEFAULT_CODAY_PRINCIPAL_TYPE: PrincipalType = 'human'

/** Error raised when a Coday identity cannot be bridged (fail-closed). */
export class CodayIdentityError extends Error {
  /** Machine-readable reason, stable for tests and logs. */
  readonly reason: string

  constructor(reason: string, message?: string) {
    super(message ?? `Invalid Coday identity: ${reason}`)
    this.name = 'CodayIdentityError'
    this.reason = reason
  }
}

/**
 * A Coday identity resolved by the trusted proxy/auth layer.
 *
 * `email` is the only required field; it MUST originate from a trusted,
 * signature-verified source (the proxy), never from an unsigned client header.
 */
export interface CodayIdentityOptions {
  /** The Coday user email resolved by the proxy. Anchor of the identity. */
  email: string
  /** Delegated scopes. Defaults to `[]` (no privilege). */
  scopes?: string[]
  /** Principal kind. Defaults to `'human'`. */
  principalType?: PrincipalType
  /** Token audience override. Defaults to the Fake IdP audience. */
  audience?: string
  /** Token lifetime in seconds. Defaults to the Fake IdP TTL. */
  expiresInSeconds?: number
}

/** A fully-validated, normalized Coday identity ready to be minted. */
export interface NormalizedCodayIdentity {
  email: string
  principalType: PrincipalType
  scopes: string[]
  audience?: string
  expiresInSeconds?: number
  secret: string
}

// Deliberately permissive-but-strict: a single `@`, a non-empty local part and
// a dotted domain, no whitespace. Anything else fails closed.
const CODAY_EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function normalizeSecret(secret: string | undefined | null): string {
  if (secret === undefined || secret === null) {
    // Dev/test default; production MUST inject an explicit secret.
    return DEFAULT_FAKE_IDP_SECRET
  }
  if (typeof secret !== 'string' || secret.length === 0) {
    throw new CodayIdentityError('invalid-secret', 'Coday identity secret must be a non-empty string when provided')
  }
  return secret
}

function normalizeScopes(scopes: unknown): string[] {
  if (scopes === undefined || scopes === null) return []
  if (!Array.isArray(scopes)) {
    throw new CodayIdentityError('invalid-scopes', 'Coday identity scopes must be an array of strings')
  }
  const normalized: string[] = []
  for (const scope of scopes) {
    if (typeof scope !== 'string' || scope.trim().length === 0) {
      throw new CodayIdentityError('invalid-scopes', 'Coday identity scopes must be non-empty strings')
    }
    normalized.push(scope.trim())
  }
  return normalized
}

function normalizePrincipalType(principalType: unknown): PrincipalType {
  if (principalType === undefined || principalType === null) return DEFAULT_CODAY_PRINCIPAL_TYPE
  if (!(CODAY_PRINCIPAL_TYPES as readonly string[]).includes(principalType as string)) {
    throw new CodayIdentityError('invalid-principal-type', "Coday identity principalType must be 'human' or 'service'")
  }
  return principalType as PrincipalType
}

function normalizeAudience(audience: unknown): string | undefined {
  if (audience === undefined || audience === null) return undefined
  if (typeof audience !== 'string' || audience.trim().length === 0) {
    throw new CodayIdentityError('invalid-audience', 'Coday identity audience must be a non-empty string when provided')
  }
  return audience.trim()
}

function normalizeExpiresInSeconds(expiresInSeconds: unknown): number | undefined {
  if (expiresInSeconds === undefined || expiresInSeconds === null) return undefined
  if (typeof expiresInSeconds !== 'number' || !Number.isFinite(expiresInSeconds)) {
    throw new CodayIdentityError(
      'invalid-expires-in',
      'Coday identity expiresInSeconds must be a finite number when provided'
    )
  }
  return expiresInSeconds
}

/**
 * Validate and normalize raw Coday identity options. Pure; throws
 * `CodayIdentityError` (fail-closed) on any invalid input.
 *
 * @param options the Coday identity to bridge
 * @param secret explicit HMAC secret; `undefined` falls back to the Fake IdP
 *   dev default, an explicitly-provided invalid secret fails closed
 */
export function normalizeCodayIdentity(options: CodayIdentityOptions, secret?: string): NormalizedCodayIdentity {
  if (!isPlainObject(options)) {
    throw new CodayIdentityError('invalid-options', 'Coday identity options must be an object')
  }
  const rawEmail = (options as { email?: unknown }).email
  if (typeof rawEmail !== 'string') {
    throw new CodayIdentityError('missing-email', 'Coday identity requires a string email')
  }
  const email = rawEmail.trim()
  if (email.length === 0) {
    throw new CodayIdentityError('blank-email', 'Coday identity email must not be blank')
  }
  if (!CODAY_EMAIL_PATTERN.test(email)) {
    throw new CodayIdentityError('invalid-email', `Coday identity email is malformed: ${email}`)
  }
  const normalized: NormalizedCodayIdentity = {
    email,
    principalType: normalizePrincipalType((options as { principalType?: unknown }).principalType),
    scopes: normalizeScopes((options as { scopes?: unknown }).scopes),
    secret: normalizeSecret(secret),
  }
  const audience = normalizeAudience((options as { audience?: unknown }).audience)
  if (audience !== undefined) normalized.audience = audience
  const expiresInSeconds = normalizeExpiresInSeconds((options as { expiresInSeconds?: unknown }).expiresInSeconds)
  if (expiresInSeconds !== undefined) normalized.expiresInSeconds = expiresInSeconds
  return normalized
}

/**
 * Mint a signed HS256 JWT for a Coday identity.
 *
 * The returned token is accepted, unchanged, by `extractTrustContext` and
 * yields `authenticationMethod: 'jwt'` with `principalId === email`.
 *
 * Fail-closed: throws `CodayIdentityError` on any invalid input (missing /
 * blank / malformed email, non-string or empty scopes, unknown
 * `principalType`, blank `audience`, non-finite `expiresInSeconds`, or an
 * explicitly-provided empty secret). Use `tryMintCodayIdentityToken` when a
 * non-throwing, `string | null` contract is preferred.
 *
 * NOTE: `options.email` must come from the trusted proxy/auth layer. Never
 * derive it from an unsigned client header.
 */
export function mintCodayIdentityToken(options: CodayIdentityOptions, secret?: string): string {
  const identity = normalizeCodayIdentity(options, secret)
  const payload = {
    principalId: identity.email,
    principalType: identity.principalType,
    scopes: identity.scopes,
    ...(identity.audience !== undefined ? { audience: identity.audience } : {}),
  }
  return identity.expiresInSeconds === undefined
    ? issueJwt(payload, identity.secret)
    : issueJwt(payload, identity.secret, identity.expiresInSeconds)
}

/**
 * Fail-closed, non-throwing variant of `mintCodayIdentityToken`.
 *
 * Returns `null` when the identity cannot be bridged so callers can simply
 * proceed without a credential rather than risk minting a malformed one.
 */
export function tryMintCodayIdentityToken(options: CodayIdentityOptions, secret?: string): string | null {
  try {
    return mintCodayIdentityToken(options, secret)
  } catch {
    return null
  }
}

/** Alias of `mintCodayIdentityToken` for readability at call sites. */
export const issueCodayIdentityToken = mintCodayIdentityToken

/**
 * Stateful bridge bound to a shared secret.
 *
 * Prefer this when the secret is resolved once at composition time; the
 * free functions remain the stateless entry points.
 */
export class CodayIdentityBridge {
  readonly #secret: string

  constructor(secret?: string) {
    this.#secret = normalizeSecret(secret)
  }

  /** Mint a signed JWT for the given Coday identity (throws on invalid input). */
  mintToken(options: CodayIdentityOptions): string {
    return mintCodayIdentityToken(options, this.#secret)
  }

  /** Non-throwing mint: `null` when the identity cannot be bridged. */
  tryMintToken(options: CodayIdentityOptions): string | null {
    return tryMintCodayIdentityToken(options, this.#secret)
  }
}
