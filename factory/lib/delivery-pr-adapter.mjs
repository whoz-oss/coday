const TRUSTED_PR_HOSTS = new Set(['github.com', 'www.github.com'])
function trustedPullRequestUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && TRUSTED_PR_HOSTS.has(url.hostname)
  } catch {
    return false
  }
}

export class DeliveryPullRequestAdapter {
  constructor({ provider = null } = {}) {
    this.provider = provider
  }

  /**
   * Find an existing PR by stable identity (head branch + base branch from trusted configuration).
   * Returns { ok: true, pullRequest } if found, { ok: true, pullRequest: null } if not found,
   * or { ok: false, error } if the provider is not configured or inspection fails.
   */
  async findExisting(context) {
    if (!this.provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    if (typeof this.provider.findExisting !== 'function') return { ok: true, pullRequest: null }
    try {
      const result = await this.provider.findExisting(context)
      if (!result) return { ok: true, pullRequest: null }
      if (!result.id || !trustedPullRequestUrl(result.url) || !result.state)
        return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
      return {
        ok: true,
        pullRequest: { id: String(result.id), url: result.url, draft: result.draft === true, state: result.state },
      }
    } catch {
      return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_INSPECTION_FAILED' } }
    }
  }

  async createDraft(context) {
    if (!this.provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    // Always inspect before creating to prevent duplication after crash/retry.
    const existing = await this.findExisting(context)
    if (!existing.ok) return existing
    if (existing.pullRequest) return { ok: true, pullRequest: existing.pullRequest, reused: true }
    try {
      const result = await this.provider.createDraft(context)
      if (!result?.id || !trustedPullRequestUrl(result.url) || result.draft !== true)
        return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
      return {
        ok: true,
        pullRequest: { id: String(result.id), url: result.url, draft: true, state: 'open' },
        reused: false,
      }
    } catch {
      return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_CREATION_FAILED' } }
    }
  }

  async inspect(context) {
    if (!this.provider) return { ok: false, blocked: true, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    const result = await this.provider.inspect(context)
    return result?.id && trustedPullRequestUrl(result.url) && result?.state
      ? { ok: true, pullRequest: result }
      : { ok: false, blocked: true, error: { code: 'PULL_REQUEST_RESULT_INDETERMINATE' } }
  }
}
