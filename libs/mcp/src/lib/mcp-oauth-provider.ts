import { OAuthClientProvider } from '@modelcontextprotocol/sdk/client/auth.js'
import {
  OAuthClientInformationFull,
  OAuthClientInformationFullSchema,
  OAuthTokens,
  OAuthTokensSchema,
} from '@modelcontextprotocol/sdk/shared/auth.js'
import { Interactor, OAuthCallbackEvent, OAuthRequestEvent } from '@coday/model'
import { UserService } from '@coday/service'

/**
 * Implements OAuthClientProvider for remote MCP servers.
 *
 * Bridges the MCP SDK OAuth flow with Coday's existing infrastructure:
 * - Token storage via UserService (projects[project].integration[mcpId].mcpOAuth)
 * - Auth URL delivery via OAuthRequestEvent emitted through Interactor
 * - Callback reception via handleCallback() which calls transport.finishAuth()
 *
 * The SDK drives the full OAuth 2.1 flow including:
 * - Protected Resource Metadata discovery (RFC9728)
 * - Authorization Server Metadata discovery (RFC8414)
 * - Dynamic Client Registration (RFC7591)
 * - PKCE (required by OAuth 2.1)
 *
 * Flow:
 * 1. transport.start() -> 401 -> SDK calls redirectToAuthorization() -> OAuthRequestEvent emitted
 * 2. User authorizes -> OAuthCallbackEvent arrives -> handleCallback() calls transport.finishAuth(code)
 * 3. SDK exchanges code for token, saves via saveTokens(), reconnects
 */
export class McpOAuthProvider implements OAuthClientProvider {
  /** In-memory PKCE code verifier for the current flow */
  private _codeVerifier: string | undefined

  /** Cached discovery state, optionally with scopes_supported stripped */
  private _discoveryState: Record<string, unknown> | undefined

  /**
   * Reference to the transport's finishAuth method, set after transport creation.
   * Called when the OAuth callback arrives with the authorization code.
   */
  private finishAuth: ((code: string) => Promise<void>) | null = null

  /**
   * Pending auth promise: created in redirectToAuthorization(), resolved/rejected in handleCallback().
   * Allows McpToolsFactory to wait for the OAuth flow to complete before retrying the connection.
   */
  private _pendingAuthPromise: Promise<void> | undefined
  private _pendingAuthResolve: (() => void) | undefined
  private _pendingAuthReject: ((err: Error) => void) | undefined

  constructor(
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectName: string,
    /** MCP server id, used as integration name for storage and event routing */
    private readonly mcpId: string,
    private readonly redirectUri: string,
    /** Pre-registered OAuth client credentials (skips dynamic registration when set) */
    private readonly staticClientId?: string,
    private readonly staticClientSecret?: string,
    /**
     * Override the OAuth scope requested during authorization.
     * When set, replaces the scope auto-detected from server metadata (scopes_supported).
     * Set to empty string to request no scope at all.
     */
    private readonly staticScope?: string
  ) {}

  get redirectUrl(): string {
    return this.redirectUri
  }

  get clientMetadata() {
    return {
      redirect_uris: [this.redirectUri],
      token_endpoint_auth_method: 'none' as const,
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      client_name: `Coday MCP Client (${this.mcpId})`,
      // When staticScope is defined (even empty string), it overrides server-advertised scopes
      ...(this.staticScope !== undefined ? { scope: this.staticScope } : {}),
    }
  }

  /**
   * Override resource URL validation to prevent the SDK from propagating
   * scopes_supported from the Protected Resource Metadata.
   * When staticScope is set, we return undefined so the SDK uses clientMetadata.scope instead.
   */
  async validateResourceURL(_serverUrl: string | URL, _resource?: string): Promise<URL | undefined> {
    if (this.staticScope !== undefined) {
      this.interactor.debug(
        `[MCP OAuth:${this.mcpId}] validateResourceURL: suppressing resource scopes (staticScope='${this.staticScope}')`
      )
      return undefined
    }
    return undefined
  }

  /**
   * Override discovery state to strip scopes_supported from resourceMetadata when oauthScope is set.
   * This prevents the SDK from auto-injecting scopes_supported into the authorization URL.
   */
  async saveDiscoveryState(state: Record<string, unknown>): Promise<void> {
    if (this.staticScope !== undefined && state.resourceMetadata) {
      const rm = state.resourceMetadata as Record<string, unknown>
      const stripped = { ...rm, scopes_supported: undefined }
      this.interactor.debug(
        `[MCP OAuth:${this.mcpId}] saveDiscoveryState: stripping scopes_supported from resourceMetadata`
      )
      this._discoveryState = { ...state, resourceMetadata: stripped }
    } else {
      this._discoveryState = state
    }
  }

  async discoveryState(): Promise<Record<string, unknown> | undefined> {
    return this._discoveryState
  }

  // Token storage

  tokens(): OAuthTokens | undefined {
    const raw = this.loadRaw()?.tokens
    if (!raw) {
      this.interactor.debug(`[MCP OAuth:${this.mcpId}] no stored tokens found`)
      return undefined
    }
    const parsed = OAuthTokensSchema.safeParse(raw)
    if (!parsed.success) {
      this.interactor.warn(
        `[MCP OAuth:${this.mcpId}] stored tokens are invalid: ${JSON.stringify(parsed.error.issues)}`
      )
      return undefined
    }
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] tokens loaded (expires_in=${parsed.data.expires_in})`)
    return parsed.data
  }

  async saveTokens(tokens: OAuthTokens): Promise<void> {
    this.ensureStoragePath().tokens = tokens
    this.userService.save()
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] tokens saved`)
  }

  // Client registration (dynamic, RFC7591)

  clientInformation(): OAuthClientInformationFull | undefined {
    // Static credentials take priority — skip dynamic registration entirely
    if (this.staticClientId) {
      this.interactor.debug(
        `[MCP OAuth:${this.mcpId}] using static client credentials (client_id=${this.staticClientId})`
      )
      return {
        client_id: this.staticClientId,
        ...(this.staticClientSecret ? { client_secret: this.staticClientSecret } : {}),
        redirect_uris: [this.redirectUri],
        token_endpoint_auth_method: this.staticClientSecret ? ('client_secret_post' as const) : ('none' as const),
        grant_types: ['authorization_code', 'refresh_token'],
        response_types: ['code'],
        client_name: `Coday MCP Client (${this.mcpId})`,
      }
    }

    const raw = this.loadRaw()?.clientInfo
    if (!raw) {
      this.interactor.debug(`[MCP OAuth:${this.mcpId}] no stored client info found`)
      return undefined
    }
    const parsed = OAuthClientInformationFullSchema.safeParse(raw)
    if (!parsed.success) {
      this.interactor.warn(
        `[MCP OAuth:${this.mcpId}] stored client info is invalid: ${JSON.stringify(parsed.error.issues)}`
      )
      return undefined
    }
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] client info loaded (client_id=${parsed.data.client_id})`)
    return parsed.data
  }

  async saveClientInformation(clientInformation: OAuthClientInformationFull): Promise<void> {
    // Don't persist if using static credentials — they come from config, not from registration
    if (this.staticClientId) {
      this.interactor.debug(`[MCP OAuth:${this.mcpId}] skipping saveClientInformation (static credentials in use)`)
      return
    }
    this.ensureStoragePath().clientInfo = clientInformation
    this.userService.save()
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] client info saved (id=${clientInformation.client_id})`)
  }

  // PKCE

  saveCodeVerifier(codeVerifier: string): void {
    this._codeVerifier = codeVerifier
  }

  codeVerifier(): string {
    if (!this._codeVerifier) throw new Error(`[MCP OAuth:${this.mcpId}] no code verifier available`)
    return this._codeVerifier
  }

  // Transport finishAuth binding

  /**
   * Register the transport's finishAuth callback.
   * Must be called right after the transport is created.
   */
  setFinishAuth(fn: (code: string) => Promise<void>): void {
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] finishAuth callback registered`)
    this.finishAuth = fn
  }

  // Authorization redirect

  /**
   * Returns the pending auth promise if an OAuth flow has been initiated but not yet completed.
   * McpToolsFactory uses this to wait for the user to complete authorization before retrying.
   */
  waitForAuth(): Promise<void> | undefined {
    return this._pendingAuthPromise
  }

  /**
   * Called by the SDK when user authorization is needed.
   * Emits an OAuthRequestEvent so the frontend can open the auth URL.
   * Also creates a pending promise that resolves when handleCallback() succeeds.
   */
  redirectToAuthorization(authorizationUrl: URL): void {
    this.interactor.warn(`[MCP OAuth:${this.mcpId}] OAuth authorization required — opening auth URL`)

    // When staticScope is explicitly set (even to empty string), strip or replace the scope
    // the SDK injected from server metadata — we want to control it ourselves.
    if (this.staticScope !== undefined) {
      const url = new URL(authorizationUrl.toString())
      if (this.staticScope === '') {
        url.searchParams.delete('scope')
        this.interactor.debug(`[MCP OAuth:${this.mcpId}] stripped scope from authorization URL`)
      } else {
        url.searchParams.set('scope', this.staticScope)
        this.interactor.debug(`[MCP OAuth:${this.mcpId}] overrode scope to '${this.staticScope}' in authorization URL`)
      }
      authorizationUrl = url
    }

    this.interactor.debug(`[MCP OAuth:${this.mcpId}] redirecting to authorization: ${authorizationUrl}`)

    // Create the pending promise so McpToolsFactory can await OAuth completion
    if (!this._pendingAuthPromise) {
      this._pendingAuthPromise = new Promise<void>((resolve, reject) => {
        this._pendingAuthResolve = resolve
        this._pendingAuthReject = reject
      })
      // Auto-clear promise reference once settled
      this._pendingAuthPromise.then(
        () => {
          this._pendingAuthPromise = undefined
        },
        () => {
          this._pendingAuthPromise = undefined
        }
      )
    }

    this.interactor.sendEvent(
      new OAuthRequestEvent({
        authUrl: authorizationUrl.toString(),
        state: authorizationUrl.searchParams.get('state') ?? '',
        integrationName: this.mcpId,
      })
    )
  }

  // Credential invalidation

  async invalidateCredentials(scope: 'all' | 'client' | 'tokens' | 'verifier'): Promise<void> {
    this.interactor.warn(`[MCP OAuth:${this.mcpId}] invalidating credentials: ${scope}`)
    const storage = this.loadRaw()

    // Reject any pending auth promise — credentials are being invalidated
    if (this._pendingAuthReject) {
      this._pendingAuthReject(new Error(`[MCP OAuth:${this.mcpId}] credentials invalidated during pending auth flow`))
      this._pendingAuthResolve = undefined
      this._pendingAuthReject = undefined
    }

    if (!storage) return

    switch (scope) {
      case 'all':
        this.clearStorage()
        break
      case 'client':
        delete storage.clientInfo
        this.userService.save()
        break
      case 'tokens':
        delete storage.tokens
        this.userService.save()
        break
      case 'verifier':
        this._codeVerifier = undefined
        break
    }
  }

  // Callback handling

  /**
   * Called by Toolbox when an OAuthCallbackEvent arrives for this MCP server.
   * Delegates to the transport's finishAuth() to complete the code exchange.
   */
  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    if (event.integrationName !== this.mcpId) {
      this.interactor.debug(`[MCP OAuth:${this.mcpId}] ignoring callback for integration '${event.integrationName}'`)
      return
    }
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] handling OAuth callback`)

    if (event.error) {
      const msg =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`
      this.interactor.warn(msg)
      if (this._pendingAuthReject) {
        this._pendingAuthReject(new Error(msg))
        this._pendingAuthResolve = undefined
        this._pendingAuthReject = undefined
      }
      return
    }

    if (!event.code) {
      const msg = `[MCP OAuth:${this.mcpId}] callback missing authorization code`
      this.interactor.error(msg)
      if (this._pendingAuthReject) {
        this._pendingAuthReject(new Error(msg))
        this._pendingAuthResolve = undefined
        this._pendingAuthReject = undefined
      }
      return
    }

    if (!this.finishAuth) {
      const msg = `[MCP OAuth:${this.mcpId}] no transport registered to receive auth code`
      this.interactor.error(msg)
      if (this._pendingAuthReject) {
        this._pendingAuthReject(new Error(msg))
        this._pendingAuthResolve = undefined
        this._pendingAuthReject = undefined
      }
      return
    }

    this.interactor.debug(`[MCP OAuth:${this.mcpId}] received authorization code, completing auth flow`)
    try {
      await this.finishAuth(event.code)
      // Resolve the pending promise so McpToolsFactory can retry the connection
      if (this._pendingAuthResolve) {
        this._pendingAuthResolve()
        this._pendingAuthResolve = undefined
        this._pendingAuthReject = undefined
      }
    } catch (err: any) {
      this.interactor.error(`[MCP OAuth:${this.mcpId}] finishAuth failed: ${err.message}`)
      if (this._pendingAuthReject) {
        this._pendingAuthReject(err)
        this._pendingAuthResolve = undefined
        this._pendingAuthReject = undefined
      }
    }
  }

  // Storage helpers

  private loadRaw(): McpOAuthStorage | undefined {
    return this.userService.config.projects?.[this.projectName]?.integration?.[this.mcpId]?.mcpOAuth
  }

  private ensureStoragePath(): McpOAuthStorage {
    const config = this.userService.config
    if (!config.projects) config.projects = {}
    if (!config.projects[this.projectName]) config.projects[this.projectName] = { integration: {} }
    const proj = config.projects[this.projectName]!
    if (!proj.integration) proj.integration = {}
    if (!proj.integration[this.mcpId]) proj.integration[this.mcpId] = {}
    const integ = proj.integration[this.mcpId]!
    if (!integ.mcpOAuth) integ.mcpOAuth = {}
    return integ.mcpOAuth as McpOAuthStorage
  }

  private clearStorage(): void {
    const proj = this.userService.config.projects?.[this.projectName]
    if (!proj?.integration?.[this.mcpId]) return
    delete proj.integration![this.mcpId]!.mcpOAuth
    this.userService.save()
  }
}

/** Shape stored under projects[project].integration[mcpId].mcpOAuth */
type McpOAuthStorage = {
  tokens?: OAuthTokens
  clientInfo?: OAuthClientInformationFull
}
