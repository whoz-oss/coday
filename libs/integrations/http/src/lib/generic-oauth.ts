/**
 * Generic OAuth2 Authorization Code Flow with PKCE
 *
 * Standard implementation using oauth4webapi.
 * Unlike BasecampOAuth, this follows the OAuth2 spec strictly.
 * Provider-specific quirks should NOT be added here.
 *
 * Token storage follows the same pattern as BasecampOAuth:
 * userService.config.projects[projectName].integration[integrationName].oauth2.tokens
 *
 * Discovery support (RFC 8414 / OIDC):
 * If authorizationEndpoint + tokenEndpoint are provided, they are used directly (existing behaviour).
 * If issuer or discoveryUrl is provided instead, endpoints are resolved automatically via
 * oauth4webapi's discoveryRequest() — trying OIDC (.well-known/openid-configuration) first,
 * then RFC 8414 (.well-known/oauth-authorization-server) as fallback.
 */
import * as oauth from 'oauth4webapi'
import { Interactor } from '@coday/model'
import { OAuthCallbackEvent, OAuthRequestEvent } from '@coday/model'
import { UserService } from '@coday/service'
import { OAuth2Tokens } from '@coday/model'

export interface GenericOAuthConfig {
  clientId: string
  clientSecret: string
  redirectUri: string
  /**
   * Direct authorization endpoint URL.
   * Required unless issuer or discoveryUrl is provided.
   */
  authorizationEndpoint?: string
  /**
   * Direct token endpoint URL.
   * Required unless issuer or discoveryUrl is provided.
   */
  tokenEndpoint?: string
  /**
   * OAuth2 / OIDC issuer URL (e.g. "https://accounts.google.com").
   * When provided, endpoints are resolved automatically via RFC 8414 / OIDC discovery.
   * Takes precedence over discoveryUrl; ignored when authorizationEndpoint + tokenEndpoint are set.
   */
  issuer?: string
  /**
   * Explicit discovery document URL.
   * Used when the discovery URL does not follow the standard .well-known convention.
   * Ignored when authorizationEndpoint + tokenEndpoint are set.
   */
  discoveryUrl?: string
  // Single string or array — joined with spaces per OAuth2 spec
  scope?: string | string[]
}

export interface TokenData {
  accessToken: string
  refreshToken?: string
  expiresAt: number
}

export class GenericOAuth {
  // Authorization server metadata — resolved lazily when discovery is needed
  private as: oauth.AuthorizationServer | null = null
  private readonly client: oauth.Client
  private readonly clientAuth: oauth.ClientAuth

  private tokenData: TokenData | null = null

  private pendingState: string | null = null
  private pendingCodeVerifier: string | null = null
  private pendingResolve: ((token: TokenData) => void) | null = null
  private pendingReject: ((error: Error) => void) | null = null
  // Shared promise for concurrent authenticate() calls during the same flow
  private pendingAuthPromise: Promise<TokenData> | null = null
  // Resolved project name: strips worktree suffix (e.g. "proj__feat-x" → "proj")
  private readonly resolvedProjectName: string

  constructor(
    private readonly config: GenericOAuthConfig,
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    projectName: string,
    private readonly integrationName: string
  ) {
    this.resolvedProjectName = userService.resolveProjectName(projectName)

    // If both endpoints are provided directly, build the AuthorizationServer immediately
    // (preserves the existing synchronous behaviour for configurations that don't need discovery)
    if (config.authorizationEndpoint && config.tokenEndpoint) {
      // Use the full authorization endpoint URL as issuer base — supports path-based issuers
      // (e.g. Keycloak realms: https://auth.example.com/realms/myrealm)
      const authUrl = new URL(config.authorizationEndpoint)
      this.as = {
        issuer: `${authUrl.origin}${authUrl.pathname.split('/').slice(0, -1).join('/')}`,
        authorization_endpoint: config.authorizationEndpoint,
        token_endpoint: config.tokenEndpoint,
      }
    }
    // Otherwise, `this.as` stays null and will be resolved lazily via discovery in resolveAuthorizationServer()

    this.client = { client_id: config.clientId }
    this.clientAuth = oauth.ClientSecretPost(config.clientSecret)
  }

  /**
   * Resolves the authorization server metadata, performing discovery if needed.
   * Cached after the first successful call.
   *
   * Discovery strategy:
   * 1. If authorizationEndpoint + tokenEndpoint were already set → already resolved in constructor, noop.
   * 2. If issuer is provided → use oauth4webapi discoveryRequest() (OIDC first, RFC 8414 fallback).
   * 3. If discoveryUrl is provided → fetch the document directly.
   * 4. Otherwise → throw a clear configuration error.
   */
  private async resolveAuthorizationServer(): Promise<oauth.AuthorizationServer> {
    if (this.as) return this.as

    const { issuer, discoveryUrl } = this.config

    if (issuer) {
      this.interactor.debug(`[OAuth:${this.integrationName}] resolving endpoints via discovery for issuer=${issuer}`)
      const issuerUrl = new URL(issuer)
      // oauth4webapi tries OIDC (.well-known/openid-configuration) by default;
      // passing algorithm: 'oauth2' makes it try RFC 8414 (.well-known/oauth-authorization-server)
      // We try OIDC first, then fall back to RFC 8414.
      let discoveryResponse: Response
      try {
        discoveryResponse = await oauth.discoveryRequest(issuerUrl, { algorithm: 'oidc' })
      } catch {
        this.interactor.debug(
          `[OAuth:${this.integrationName}] OIDC discovery failed, falling back to RFC 8414 for issuer=${issuer}`
        )
        discoveryResponse = await oauth.discoveryRequest(issuerUrl, { algorithm: 'oauth2' })
      }
      const server = await oauth.processDiscoveryResponse(issuerUrl, discoveryResponse)
      this.interactor.debug(
        `[OAuth:${this.integrationName}] discovery succeeded: authorization_endpoint=${server.authorization_endpoint}, token_endpoint=${server.token_endpoint}`
      )
      this.as = server
      return this.as
    }

    if (discoveryUrl) {
      this.interactor.debug(
        `[OAuth:${this.integrationName}] fetching discovery document from explicit URL: ${discoveryUrl}`
      )
      const response = await fetch(discoveryUrl)
      if (!response.ok) {
        throw new Error(
          `[OAuth:${this.integrationName}] failed to fetch discovery document from ${discoveryUrl}: HTTP ${response.status}`
        )
      }
      const metadata = (await response.json()) as oauth.AuthorizationServer
      if (!metadata.authorization_endpoint || !metadata.token_endpoint) {
        throw new Error(
          `[OAuth:${this.integrationName}] discovery document at ${discoveryUrl} is missing authorization_endpoint or token_endpoint`
        )
      }
      // Derive the issuer from the discovery URL if not present in the document
      const parsedDiscovery = new URL(discoveryUrl)
      this.as = {
        ...metadata,
        issuer: metadata.issuer ?? parsedDiscovery.origin,
      }
      return this.as
    }

    throw new Error(
      `[OAuth:${this.integrationName}] OAuth configuration error: provide either ` +
        `authorizationEndpoint + tokenEndpoint, or issuer, or discoveryUrl`
    )
  }

  /** True if we have a token in memory or storage (may be expired) */
  hasToken(): boolean {
    if (this.tokenData) return true
    this.loadTokensFromStorage()
    return !!this.tokenData
  }

  /** True if we have a refresh token available */
  hasRefreshToken(): boolean {
    if (!this.tokenData) this.loadTokensFromStorage()
    return !!this.tokenData?.refreshToken
  }

  isAuthenticated(): boolean {
    if (!this.tokenData) {
      this.interactor.debug(`[OAuth:${this.integrationName}] no in-memory token, trying storage`)
      this.loadTokensFromStorage()
    }
    if (!this.tokenData) {
      this.interactor.debug(`[OAuth:${this.integrationName}] no token found in storage`)
      return false
    }
    const remainingMs = this.tokenData.expiresAt - Date.now()
    const valid = remainingMs > 5 * 60 * 1000
    this.interactor.debug(
      `[OAuth:${this.integrationName}] token expiresAt=${new Date(this.tokenData.expiresAt).toISOString()}, remainingMs=${remainingMs}, valid=${valid}`
    )
    return valid
  }

  async getAccessToken(): Promise<string> {
    if (!this.tokenData) {
      this.loadTokensFromStorage()
    }
    if (!this.tokenData) {
      throw new Error('Not authenticated. Call authenticate() first.')
    }
    if (!this.isAuthenticated()) {
      if (this.tokenData.refreshToken) {
        this.interactor.debug(`[OAuth:${this.integrationName}] token expired, attempting refresh`)
        await this.refreshToken()
        this.interactor.debug(`[OAuth:${this.integrationName}] token refreshed successfully`)
      } else {
        // No refresh_token: HttpTools is responsible for calling authenticate() in this case
        this.interactor.debug(
          `[OAuth:${this.integrationName}] token expired and no refresh_token, returning expired token`
        )
      }
    }
    return this.tokenData!.accessToken
  }

  /**
   * Initiates the OAuth2 Authorization Code + PKCE flow.
   * Emits an OAuthRequestEvent for the frontend to open the auth URL.
   * Returns a Promise resolved by handleCallback() when the user completes auth.
   *
   * If a flow is already in progress, returns the same pending Promise (no double flow).
   * Adds access_type=offline and prompt=consent to ensure a refresh_token is returned.
   */
  authenticate(): Promise<TokenData> {
    if (this.isAuthenticated()) {
      return Promise.resolve(this.tokenData!)
    }

    // Guard: if a flow is already in progress, reuse the same promise
    if (this.pendingAuthPromise) {
      this.interactor.debug(`[OAuth:${this.integrationName}] OAuth flow already in progress, reusing pending promise`)
      return this.pendingAuthPromise
    }

    // Create the shared promise immediately so concurrent calls get the same reference
    this.pendingAuthPromise = new Promise((resolve, reject) => {
      this.pendingResolve = resolve
      this.pendingReject = reject
    })

    // Clear the shared promise reference once it settles (success or failure)
    this.pendingAuthPromise.then(
      () => {
        this.pendingAuthPromise = null
      },
      () => {
        this.pendingAuthPromise = null
      }
    )

    // Kick off the async flow separately
    this.startAuthFlow().catch((err) => this.rejectPending(err))

    return this.pendingAuthPromise
  }

  private async startAuthFlow(): Promise<void> {
    // Clear expired/invalid token so the flow starts fresh
    this.tokenData = null
    this.clearTokensFromStorage()
    this.interactor.debug(`[OAuth:${this.integrationName}] starting fresh OAuth flow`)

    // Resolve the authorization server (performs discovery if needed)
    const as = await this.resolveAuthorizationServer()

    const state = oauth.generateRandomState()
    const codeVerifier = oauth.generateRandomCodeVerifier()
    const codeChallenge = await oauth.calculatePKCECodeChallenge(codeVerifier)

    this.pendingState = state
    this.pendingCodeVerifier = codeVerifier

    const authorizationUrl = new URL(as.authorization_endpoint!)
    authorizationUrl.searchParams.set('client_id', this.client.client_id)
    authorizationUrl.searchParams.set('redirect_uri', this.config.redirectUri)
    authorizationUrl.searchParams.set('response_type', 'code')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('code_challenge', codeChallenge)
    authorizationUrl.searchParams.set('code_challenge_method', 'S256')
    // Request offline access to get a refresh_token; prompt=consent ensures it's always returned
    authorizationUrl.searchParams.set('access_type', 'offline')
    authorizationUrl.searchParams.set('prompt', 'consent')
    if (this.config.scope) {
      const scope = Array.isArray(this.config.scope) ? this.config.scope.join(' ') : this.config.scope
      authorizationUrl.searchParams.set('scope', scope)
    }

    this.interactor.debug(
      `[OAuth:${this.integrationName}] emitting OAuthRequestEvent, authUrl=${authorizationUrl.toString()}`
    )
    this.interactor.sendEvent(
      new OAuthRequestEvent({
        authUrl: authorizationUrl.toString(),
        state,
        integrationName: this.integrationName,
      })
    )
    this.interactor.debug(`[OAuth:${this.integrationName}] waiting for OAuth callback...`)
  }

  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    if (event.integrationName !== this.integrationName) return

    if (event.state !== this.pendingState) {
      const err = new Error('Invalid OAuth state')
      this.interactor.error(err.message)
      this.rejectPending(err)
      return
    }

    if (event.error) {
      const errorMessage =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`
      this.interactor.warn(errorMessage)
      this.rejectPending(new Error(errorMessage))
      return
    }

    if (!event.code || !this.pendingCodeVerifier || !this.pendingState) {
      const err = new Error('No pending OAuth flow or missing code')
      this.interactor.error(err.message)
      this.rejectPending(err)
      return
    }

    // as is guaranteed to be set here: startAuthFlow() always calls resolveAuthorizationServer() first
    const as = this.as!

    try {
      const callbackUrl = new URL(this.config.redirectUri)
      callbackUrl.searchParams.set('code', event.code)
      callbackUrl.searchParams.set('state', event.state)

      const params = oauth.validateAuthResponse(as, this.client, callbackUrl, this.pendingState)

      const response = await oauth.authorizationCodeGrantRequest(
        as,
        this.client,
        this.clientAuth,
        params,
        this.config.redirectUri,
        this.pendingCodeVerifier
      )

      const result = await oauth.processAuthorizationCodeResponse(as, this.client, response)

      this.tokenData = {
        accessToken: result.access_token,
        refreshToken: result.refresh_token,
        expiresAt: Date.now() + (result.expires_in ?? 3600) * 1000,
      }

      this.saveTokensToStorage()
      this.interactor.displayText(`OAuth authentication successful for ${this.integrationName}`)

      if (this.pendingResolve) {
        this.pendingResolve(this.tokenData)
        this.pendingResolve = null
      }
    } catch (error: any) {
      this.interactor.error(`OAuth token exchange failed: ${error.message}`)
      this.rejectPending(error)
    } finally {
      this.pendingState = null
      this.pendingCodeVerifier = null
    }
  }

  /** Rejects and clears all pending promise references */
  private rejectPending(error: Error): void {
    if (this.pendingReject) {
      this.pendingReject(error)
      this.pendingReject = null
    }
    this.pendingResolve = null
    this.pendingState = null
    this.pendingCodeVerifier = null
  }

  private async refreshToken(): Promise<void> {
    if (!this.tokenData?.refreshToken) {
      throw new Error('No refresh token available')
    }

    // Ensure the authorization server is resolved before attempting refresh
    const as = await this.resolveAuthorizationServer()

    try {
      const response = await oauth.refreshTokenGrantRequest(
        as,
        this.client,
        this.clientAuth,
        this.tokenData.refreshToken
      )

      const result = await oauth.processRefreshTokenResponse(as, this.client, response)

      this.tokenData = {
        accessToken: result.access_token,
        refreshToken: result.refresh_token ?? this.tokenData.refreshToken,
        expiresAt: Date.now() + (result.expires_in ?? 3600) * 1000,
      }

      this.saveTokensToStorage()
    } catch (error: any) {
      this.tokenData = null
      this.clearTokensFromStorage()
      throw new Error(`Token refresh failed: ${error.message}`)
    }
  }

  private loadTokensFromStorage(): void {
    const userProjects = this.userService.config.projects
    const projectConfig = userProjects?.[this.resolvedProjectName]
    const integrationConfig = projectConfig?.integration?.[this.integrationName]
    const tokens = integrationConfig?.oauth2?.tokens

    this.interactor.debug(
      `[OAuth:${this.integrationName}] loadTokensFromStorage: projectName=${this.resolvedProjectName}, hasProjects=${!!userProjects}, hasProjectConfig=${!!projectConfig}, hasIntegrationConfig=${!!integrationConfig}, hasTokens=${!!tokens}`
    )

    if (tokens) {
      this.tokenData = {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        expiresAt: tokens.expires_at,
      }
      this.interactor.debug(
        `[OAuth:${this.integrationName}] loaded tokens from storage, expiresAt=${new Date(tokens.expires_at).toISOString()}`
      )
    }
  }

  private saveTokensToStorage(): void {
    if (!this.tokenData) return

    const userConfig = this.userService.config
    if (!userConfig.projects) userConfig.projects = {}
    if (!userConfig.projects[this.resolvedProjectName])
      userConfig.projects[this.resolvedProjectName] = { integration: {} }
    if (!userConfig.projects[this.resolvedProjectName]!.integration)
      userConfig.projects[this.resolvedProjectName]!.integration = {}
    if (!userConfig.projects[this.resolvedProjectName]!.integration![this.integrationName]) {
      userConfig.projects[this.resolvedProjectName]!.integration![this.integrationName] = {}
    }
    if (!userConfig.projects[this.resolvedProjectName]!.integration![this.integrationName]!.oauth2) {
      userConfig.projects[this.resolvedProjectName]!.integration![this.integrationName]!.oauth2 = {} as any
    }

    const oauth2Config = userConfig.projects[this.resolvedProjectName]!.integration![this.integrationName]!.oauth2!
    oauth2Config.tokens = {
      access_token: this.tokenData.accessToken,
      refresh_token: this.tokenData.refreshToken,
      expires_at: this.tokenData.expiresAt,
    } as OAuth2Tokens

    this.userService.save()
    this.interactor.debug(`[OAuth:${this.integrationName}] saved tokens to storage`)
  }

  /** Clears tokens from memory and storage, forcing a fresh OAuth flow on next request */
  invalidateTokens(): void {
    this.tokenData = null
    this.clearTokensFromStorage()
    this.interactor.debug(`[OAuth:${this.integrationName}] tokens invalidated`)
  }

  private clearTokensFromStorage(): void {
    const oauth2Config =
      this.userService.config.projects?.[this.resolvedProjectName]?.integration?.[this.integrationName]?.oauth2
    if (oauth2Config) {
      delete oauth2Config.tokens
      this.userService.save()
      this.interactor.debug(`[OAuth:${this.integrationName}] cleared tokens from storage`)
    }
  }
}
