/**
 * Identity propagation for the reverse proxies (/api/agentos, /api/factory).
 *
 * `http-proxy-middleware` already forwards every incoming header. What it cannot
 * do on its own is derive the *caller identity* that the upstream services expect
 * in `X-External-User-Id` (AgentOS trusts it unconditionally, ahead of the
 * Cloudflare / x-forwarded-email fallbacks).
 *
 * Rules:
 *  - `--auth` enabled: the identity resolved by Express (Cloudflare JWT or
 *    `x-forwarded-email`) is set as `x-external-user-id`, overwriting anything the
 *    client sent (a client must never be able to spoof it). If no identity can be
 *    resolved, any client-supplied value is removed (fail closed).
 *  - `--auth` disabled: the static `AGENTOS_EXTERNAL_USERID` env var is used when
 *    set; otherwise the request is left untouched.
 */

export const EXTERNAL_USER_ID_HEADER = 'x-external-user-id'

export type ProxyIdentityAction = { action: 'set'; value: string } | { action: 'remove' } | { action: 'keep' }

export interface ProxyIdentityOptions {
  /** Whether `--auth` mode is active. */
  authEnabled: boolean
  /** Static identity from `AGENTOS_EXTERNAL_USERID`, used when auth is disabled. */
  envUserId?: string
  /** Resolves the authenticated username for the current request; may throw. */
  resolveUser: () => string
}

export function decideProxyIdentity(options: ProxyIdentityOptions): ProxyIdentityAction {
  const { authEnabled, envUserId, resolveUser } = options

  if (authEnabled) {
    try {
      const user = resolveUser()
      return user ? { action: 'set', value: user } : { action: 'remove' }
    } catch {
      return { action: 'remove' }
    }
  }

  if (envUserId) {
    return { action: 'set', value: envUserId }
  }
  return { action: 'keep' }
}

/** Minimal shape of the outgoing proxy request (Node `ClientRequest`). */
export interface HeaderSettable {
  setHeader(name: string, value: string): unknown
  removeHeader(name: string): unknown
}

/** Apply a [ProxyIdentityAction] to the outgoing proxy request. */
export function applyProxyIdentity(proxyReq: HeaderSettable, decision: ProxyIdentityAction): void {
  // Node header names are case-insensitive: set/remove replace any client-sent duplicate.
  if (decision.action === 'set') {
    proxyReq.setHeader(EXTERNAL_USER_ID_HEADER, decision.value)
  } else if (decision.action === 'remove') {
    proxyReq.removeHeader(EXTERNAL_USER_ID_HEADER)
  }
}
