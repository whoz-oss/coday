/**
 * Pull-request adapter for delivery operations.
 *
 * Only HTTPS pull-request URLs on trusted hosts are accepted; every provider
 * result that cannot be trusted is reported as indeterminate so callers can
 * journal the failure instead of acting on it. Creation always inspects first
 * to stay idempotent across crash/retry.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-pr-adapter.mjs` is a stateless compatibility facade
 * re-exporting from that bundle.
 */

const TRUSTED_PR_HOSTS = new Set(['github.com', 'www.github.com'])
function trustedPullRequestUrl(value: unknown): boolean {
  try {
    const url = new URL(value as string)
    return url.protocol === 'https:' && TRUSTED_PR_HOSTS.has(url.hostname)
  } catch {
    return false
  }
}

/** Raw shape a pull-request provider returns. */
export interface DeliveryPullRequestProviderResult {
  id?: unknown
  url?: unknown
  draft?: unknown
  state?: unknown
  [key: string]: unknown
}

/** The provider surface (e.g. a GitHub client) the adapter delegates to. */
export interface DeliveryPullRequestProvider {
  findExisting: (context: unknown) => Promise<DeliveryPullRequestProviderResult | null | undefined>
  createDraft: (context: unknown) => Promise<DeliveryPullRequestProviderResult | null | undefined>
  inspect: (context: unknown) => Promise<DeliveryPullRequestProviderResult | null | undefined>
}

/** A trusted pull-request projection. */
export interface DeliveryPullRequest {
  id: string
  url: string
  draft: boolean
  state: string
}

/** Result of a pull-request operation. */
export type DeliveryPullRequestResult =
  | { ok: true; pullRequest: DeliveryPullRequest | DeliveryPullRequestProviderResult | null; reused?: boolean }
  | { ok: false; blocked: true; error: { code: string } }

/** Options accepted by `DeliveryPullRequestAdapter`. */
export interface DeliveryPullRequestAdapterOptions {
  provider?: DeliveryPullRequestProvider | null
}

export class DeliveryPullRequestAdapter {
  private readonly provider: DeliveryPullRequestProvider | null

  constructor({ provider = null }: DeliveryPullRequestAdapterOptions = {}) {
    this.provider = provider
  }

  /**
   * Find an existing PR by stable identity (head branch + base branch from trusted configuration).
   * Returns { ok: true, pullRequest } if found, { ok: true, pullRequest: null } if not found,
   * or { ok: false, error } if the provider is not configured or inspection fails.
   */
  async findExisting(context: unknown): Promise<DeliveryPullRequestResult> {
    const provider = this.provider
    if (!provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    if (typeof provider.findExisting !== 'function') return { ok: true, pullRequest: null }
    try {
      const result = await provider.findExisting(context)
      if (!result) return { ok: true, pullRequest: null }
      if (!result.id || !trustedPullRequestUrl(result.url) || !result.state)
        return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
      return {
        ok: true,
        pullRequest: {
          id: String(result.id),
          url: result.url as string,
          draft: result.draft === true,
          state: result.state as string,
        },
      }
    } catch {
      return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_INSPECTION_FAILED' } }
    }
  }

  async createDraft(context: unknown): Promise<DeliveryPullRequestResult> {
    const provider = this.provider
    if (!provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    // Always inspect before creating to prevent duplication after crash/retry.
    const existing = await this.findExisting(context)
    if (!existing.ok) return existing
    if (existing.pullRequest) return { ok: true, pullRequest: existing.pullRequest, reused: true }
    try {
      const result = await provider.createDraft(context)
      if (!result?.id || !trustedPullRequestUrl(result.url) || result.draft !== true)
        return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
      return {
        ok: true,
        pullRequest: { id: String(result.id), url: result.url as string, draft: true, state: 'open' },
        reused: false,
      }
    } catch {
      return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_CREATION_FAILED' } }
    }
  }

  async inspect(context: unknown): Promise<DeliveryPullRequestResult> {
    const provider = this.provider
    if (!provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    const result = await provider.inspect(context)
    return result?.id && trustedPullRequestUrl(result.url) && result?.state
      ? { ok: true, pullRequest: result }
      : { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
  }
}
