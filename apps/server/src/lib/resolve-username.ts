/**
 * Resolves the authenticated username from an incoming HTTP request.
 *
 * Resolution order when `--auth` is enabled:
 *  1. `CF_Authorization` header — Cloudflare Access JWT. The payload (middle
 *     base64 segment) is decoded and the `email` claim is extracted. No
 *     signature verification is performed here: Cloudflare already validated
 *     the token at the edge before forwarding the request.
 *  2. `x-forwarded-email` header — standard reverse-proxy header.
 *  3. Throws an error if neither source yields a valid email.
 *
 * When `--auth` is NOT enabled (local / development mode), the OS username is
 * returned directly.
 */

import * as os from 'node:os'

const CF_JWT_HEADER = 'cf_authorization'
const EMAIL_HEADER = 'x-forwarded-email'

/**
 * Attempt to extract the `email` claim from a Cloudflare Access JWT.
 *
 * The JWT payload is the second dot-separated segment, base64url-encoded.
 * We only decode it — signature verification is intentionally skipped because
 * Cloudflare validates the token before forwarding.
 *
 * @param token - Raw JWT string from the `CF_Authorization` header.
 * @returns The email string, or `null` if the token is absent / malformed.
 */
export function extractEmailFromCfJwt(token: string | undefined): string | null {
  if (!token) return null

  const parts = token.split('.')
  if (parts.length !== 3) return null

  try {
    // base64url -> base64 -> JSON
    const payload = parts[1]!.replace(/-/g, '+').replace(/_/g, '/')
    const json = Buffer.from(payload, 'base64').toString('utf8')
    const claims = JSON.parse(json)
    const email = claims?.email
    return typeof email === 'string' && email.length > 0 ? email : null
  } catch {
    return null
  }
}

/**
 * Resolve the username for an incoming Express request.
 *
 * @param headers     - HTTP request headers (lowercased by Express).
 * @param authEnabled - Whether `--auth` mode is active.
 * @returns The resolved username.
 * @throws Error if `authEnabled` is true and no valid identity can be found.
 */
export function resolveUsername(headers: Record<string, string | string[] | undefined>, authEnabled: boolean): string {
  if (!authEnabled) {
    return os.userInfo().username
  }

  // 1. Cloudflare Access JWT
  const cfToken = headers[CF_JWT_HEADER] as string | undefined
  const cfEmail = extractEmailFromCfJwt(cfToken)
  if (cfEmail) return cfEmail

  // 2. Standard reverse-proxy header
  const fwdEmail = headers[EMAIL_HEADER] as string | undefined
  if (fwdEmail && fwdEmail.length > 0) return fwdEmail

  // 3. No valid identity found
  throw new Error(
    'Authentication required but no valid identity header found. ' +
      'Expected either a CF_Authorization JWT (Cloudflare Access) or ' +
      'an x-forwarded-email header from the upstream auth proxy.'
  )
}
