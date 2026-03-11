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

  /**
   * Reference to the transport's finishAuth method, set after transport creation.
   * Called when the OAuth callback arrives with the authorization code.
   */
  private finishAuth: ((code: string) => Promise<void>) | null = null

  constructor(
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectName: string,
    /** MCP server id, used as integration name for storage and event routing */
    private readonly mcpId: string,
    private readonly redirectUri: string
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
    }
  }

  // Token storage

  tokens(): OAuthTokens | undefined {
    const raw = this.loadRaw()?.tokens
    if (!raw) return undefined
    const parsed = OAuthTokensSchema.safeParse(raw)
    return parsed.success ? parsed.data : undefined
  }

  async saveTokens(tokens: OAuthTokens): Promise<void> {
    this.ensureStoragePath().tokens = tokens
    this.userService.save()
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] tokens saved`)
  }

  // Client registration (dynamic, RFC7591)

  clientInformation(): OAuthClientInformationFull | undefined {
    const raw = this.loadRaw()?.clientInfo
    if (!raw) return undefined
    const parsed = OAuthClientInformationFullSchema.safeParse(raw)
    return parsed.success ? parsed.data : undefined
  }

  async saveClientInformation(clientInformation: OAuthClientInformationFull): Promise<void> {
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
    this.finishAuth = fn
  }

  // Authorization redirect

  /**
   * Called by the SDK when user authorization is needed.
   * Emits an OAuthRequestEvent so the frontend can open the auth URL.
   */
  redirectToAuthorization(authorizationUrl: URL): void {
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] redirecting to authorization: ${authorizationUrl}`)
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
    this.interactor.debug(`[MCP OAuth:${this.mcpId}] invalidating credentials: ${scope}`)
    const storage = this.loadRaw()
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
    if (event.integrationName !== this.mcpId) return

    if (event.error) {
      const msg =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`
      this.interactor.warn(msg)
      return
    }

    if (!event.code) {
      this.interactor.error(`[MCP OAuth:${this.mcpId}] callback missing authorization code`)
      return
    }

    if (!this.finishAuth) {
      this.interactor.error(`[MCP OAuth:${this.mcpId}] no transport registered to receive auth code`)
      return
    }

    this.interactor.debug(`[MCP OAuth:${this.mcpId}] received authorization code, completing auth flow`)
    try {
      await this.finishAuth(event.code)
    } catch (err: any) {
      this.interactor.error(`[MCP OAuth:${this.mcpId}] finishAuth failed: ${err.message}`)
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
