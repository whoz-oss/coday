/**
 * OAuth2 handler for HTTP integrations
 * Uses oauth4webapi for secure OAuth2 implementation with PKCE
 */

import * as oauth from 'oauth4webapi'
import { Interactor, OAuthCallbackEvent, OAuthRequestEvent, OAuth2Tokens } from '@coday/model'
import { UserService } from '@coday/service'
import { HttpOAuth2Auth } from './http-config'

export class HttpOAuth {
  // OAuth4webapi configuration
  private as: oauth.AuthorizationServer
  private client: oauth.Client
  private clientAuth: oauth.ClientAuth

  // OAuth state management
  private tokens: OAuth2Tokens | null = null
  private pendingState: string | null = null
  private pendingCodeVerifier: string | null = null
  private pendingResolve: ((tokens: OAuth2Tokens) => void) | null = null
  private pendingReject: ((error: Error) => void) | null = null

  constructor(
    clientId: string, // Used in oauth4webapi client/clientAuth
    clientSecret: string, // Used in oauth4webapi clientAuth
    private readonly redirectUri: string,
    private readonly authConfig: HttpOAuth2Auth,
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectName: string,
    private readonly integrationName: string
  ) {
    // Configure OAuth authorization server
    this.as = {
      issuer: this.getIssuer(),
      authorization_endpoint: authConfig.authorizationEndpoint,
      token_endpoint: authConfig.tokenEndpoint,
    }

    this.client = { client_id: clientId }
    this.clientAuth = oauth.ClientSecretPost(clientSecret)
  }

  /**
   * Get issuer URL based on provider
   */
  private getIssuer(): string {
    if (this.authConfig.provider === 'google-sso') {
      return 'https://accounts.google.com'
    }
    // For standard OAuth2, issuer can be derived from endpoints
    return this.authConfig.authorizationEndpoint?.split('/oauth')[0] || 'https://oauth.example.com'
  }

  /**
   * Initialize OAuth - load existing tokens or prompt for authorization
   */
  async initialize(): Promise<void> {
    // Try to load existing tokens from user config
    this.loadTokensFromStorage()

    if (this.tokens) {
      // Check if token is expired
      if (this.isTokenExpired()) {
        this.interactor.displayText(`🔄 OAuth token expired for ${this.integrationName}, refreshing...`)
        await this.refreshAccessToken()
      } else {
        this.interactor.displayText(`✅ Using existing OAuth token for ${this.integrationName}`)
      }
    } else {
      // No tokens, need to authorize
      await this.authenticate()
    }
  }

  /**
   * Load tokens from user config storage
   */
  private loadTokensFromStorage(): void {
    const userConfig = this.userService.config
    const oauth2 = userConfig.projects?.[this.projectName]?.integration?.[this.integrationName]?.oauth2
    const tokens = oauth2?.tokens

    if (tokens) {
      this.tokens = tokens
      this.interactor.debug(`Loaded OAuth tokens from storage for ${this.integrationName}`)
    }
  }

  /**
   * Check if current token is expired
   */
  private isTokenExpired(): boolean {
    if (!this.tokens?.expires_at) return true
    return Date.now() >= this.tokens.expires_at
  }

  /**
   * Authenticate with OAuth2 using PKCE
   */
  async authenticate(): Promise<OAuth2Tokens> {
    if (this.isAuthenticated()) {
      return this.tokens!
    }

    // Generate PKCE challenge and state (secure by default)
    const state = oauth.generateRandomState()
    const codeVerifier = oauth.generateRandomCodeVerifier()
    const codeChallenge = await oauth.calculatePKCECodeChallenge(codeVerifier)

    // Store for callback validation
    this.pendingState = state
    this.pendingCodeVerifier = codeVerifier

    // Build authorization URL with PKCE
    const authorizationUrl = new URL(this.as.authorization_endpoint!)
    authorizationUrl.searchParams.set('client_id', this.client.client_id)
    authorizationUrl.searchParams.set('redirect_uri', this.redirectUri)
    authorizationUrl.searchParams.set('response_type', 'code')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('code_challenge', codeChallenge)
    authorizationUrl.searchParams.set('code_challenge_method', 'S256')
    if (this.authConfig.scope) {
      authorizationUrl.searchParams.set('scope', this.authConfig.scope)
    }

    // Send OAuth request event to frontend
    this.interactor.sendEvent(
      new OAuthRequestEvent({
        authUrl: authorizationUrl.toString(),
        state: state,
        integrationName: this.integrationName,
      })
    )

    // Return promise that will be resolved by handleCallback()
    return new Promise((resolve, reject) => {
      this.pendingResolve = resolve
      this.pendingReject = reject
    })
  }

  /**
   * Check if authenticated
   */
  isAuthenticated(): boolean {
    return this.tokens !== null && !this.isTokenExpired()
  }

  /**
   * Handle OAuth callback with state validation
   */
  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    if (event.integrationName !== this.integrationName) return

    this.interactor.debug(`📥 Received OAuth callback for ${this.integrationName}`)

    // Validate state (CSRF protection)
    if (event.state !== this.pendingState) {
      const error = new Error('Invalid OAuth state - possible CSRF attack')
      this.interactor.error(error.message)
      if (this.pendingReject) {
        this.pendingReject(error)
        this.pendingReject = null
      }
      this.cleanup()
      return
    }

    // Handle OAuth errors
    if (event.error) {
      const errorMessage =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : event.error === 'user_cancelled'
            ? 'OAuth authentication cancelled by user'
            : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`

      this.interactor.warn(errorMessage)

      if (this.pendingReject) {
        this.pendingReject(new Error(errorMessage))
        this.pendingReject = null
      }

      this.cleanup()
      return
    }

    if (!event.code || !this.pendingCodeVerifier) {
      const error = new Error('No authorization code or code verifier')
      this.interactor.error(error.message)
      if (this.pendingReject) {
        this.pendingReject(error)
        this.pendingReject = null
      }
      this.cleanup()
      return
    }

    try {
      // Build callback URL for validation
      const callbackUrl = new URL(this.redirectUri)
      callbackUrl.searchParams.set('code', event.code)
      callbackUrl.searchParams.set('state', event.state)

      // Validate authorization response
      const params = oauth.validateAuthResponse(this.as, this.client, callbackUrl, this.pendingState)

      // Exchange code for tokens with PKCE
      const response = await oauth.authorizationCodeGrantRequest(
        this.as,
        this.client,
        this.clientAuth,
        params,
        this.redirectUri,
        this.pendingCodeVerifier
      )

      // Process and validate token response
      const result = await oauth.processAuthorizationCodeResponse(this.as, this.client, response)

      // Store tokens
      this.tokens = {
        access_token: result.access_token,
        refresh_token: result.refresh_token,
        expires_at: Date.now() + (result.expires_in ?? 3600) * 1000,
        token_type: result.token_type || 'Bearer',
        scope: this.authConfig.scope,
      }

      this.saveTokensToStorage()

      this.interactor.displayText(`✅ OAuth authorization successful for ${this.integrationName}`)

      // Resolve authentication promise
      if (this.pendingResolve) {
        this.pendingResolve(this.tokens)
        this.pendingResolve = null
      }

      this.cleanup()
    } catch (error: any) {
      this.interactor.error(`OAuth token exchange failed: ${error.message}`)
      if (this.pendingReject) {
        this.pendingReject(error)
        this.pendingReject = null
      }
      this.cleanup()
    }
  }

  /**
   * Cleanup pending OAuth state
   */
  private cleanup(): void {
    this.pendingState = null
    this.pendingCodeVerifier = null
  }

  /**
   * Refresh access token using refresh token
   */
  private async refreshAccessToken(): Promise<void> {
    if (!this.tokens?.refresh_token) {
      // No refresh token, need to re-authorize
      this.tokens = null
      await this.authenticate()
      return
    }

    try {
      // Use oauth4webapi for secure token refresh
      const response = await oauth.refreshTokenGrantRequest(
        this.as,
        this.client,
        this.clientAuth,
        this.tokens.refresh_token
      )

      const result = await oauth.processRefreshTokenResponse(this.as, this.client, response)

      // Update tokens
      this.tokens = {
        access_token: result.access_token,
        refresh_token: result.refresh_token ?? this.tokens.refresh_token, // Keep old if not provided
        expires_at: Date.now() + (result.expires_in ?? 3600) * 1000,
        token_type: result.token_type || 'Bearer',
        scope: this.authConfig.scope,
      }

      this.saveTokensToStorage()
      this.interactor.displayText(`✅ OAuth token refreshed for ${this.integrationName}`)
    } catch (error: any) {
      this.interactor.error(`Failed to refresh token: ${error.message}`)
      // Clear invalid tokens and re-authenticate
      this.tokens = null
      this.clearTokensFromStorage()
      await this.authenticate()
    }
  }

  /**
   * Save tokens to user config storage
   */
  private saveTokensToStorage(): void {
    if (!this.tokens) return

    const userConfig = this.userService.config

    // Ensure nested structure exists
    if (!userConfig.projects) userConfig.projects = {}
    if (!userConfig.projects[this.projectName]) userConfig.projects[this.projectName] = { integration: {} }
    if (!userConfig.projects[this.projectName]!.integration) userConfig.projects[this.projectName]!.integration = {}
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]) {
      userConfig.projects[this.projectName]!.integration![this.integrationName] = {}
    }
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2) {
      userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2 = {} as any
    }

    // Save tokens
    const oauth2Config = userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2!
    oauth2Config.tokens = this.tokens

    this.userService.save()
    this.interactor.debug(`Saved OAuth tokens to storage for ${this.integrationName}`)
  }

  /**
   * Clear tokens from user config storage
   */
  private clearTokensFromStorage(): void {
    const userConfig = this.userService.config
    const oauth2Config = userConfig.projects?.[this.projectName]?.integration?.[this.integrationName]?.oauth2

    if (oauth2Config) {
      delete oauth2Config.tokens
      this.userService.save()
      this.interactor.debug(`Cleared OAuth tokens from storage for ${this.integrationName}`)
    }
  }

  /**
   * Get current access token (refresh if needed)
   */
  async getAccessToken(): Promise<string> {
    if (!this.tokens) {
      await this.initialize()
    }

    if (this.isTokenExpired()) {
      await this.refreshAccessToken()
    }

    if (!this.tokens) {
      throw new Error('Failed to get access token')
    }

    return this.tokens.access_token
  }
}
