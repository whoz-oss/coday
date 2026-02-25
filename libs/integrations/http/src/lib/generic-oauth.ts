/**
 * Generic OAuth2 Authorization Code Flow with PKCE
 *
 * Standard implementation using oauth4webapi.
 * Unlike BasecampOAuth, this follows the OAuth2 spec strictly.
 * Provider-specific quirks should NOT be added here.
 *
 * Token storage follows the same pattern as BasecampOAuth:
 * userService.config.projects[projectName].integration[integrationName].oauth2.tokens
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
  authorizationEndpoint: string
  tokenEndpoint: string
  // Single string or array — joined with spaces per OAuth2 spec
  scope?: string | string[]
}

export interface TokenData {
  accessToken: string
  refreshToken?: string
  expiresAt: number
}

export class GenericOAuth {
  private readonly as: oauth.AuthorizationServer
  private readonly client: oauth.Client
  private readonly clientAuth: oauth.ClientAuth

  private tokenData: TokenData | null = null

  private pendingState: string | null = null
  private pendingCodeVerifier: string | null = null
  private pendingResolve: ((token: TokenData) => void) | null = null
  private pendingReject: ((error: Error) => void) | null = null

  constructor(
    private readonly config: GenericOAuthConfig,
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectName: string,
    private readonly integrationName: string
  ) {
    this.as = {
      issuer: new URL(config.authorizationEndpoint).origin,
      authorization_endpoint: config.authorizationEndpoint,
      token_endpoint: config.tokenEndpoint,
    }
    this.client = { client_id: config.clientId }
    this.clientAuth = oauth.ClientSecretPost(config.clientSecret)
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
    if (!this.isAuthenticated() && this.tokenData.refreshToken) {
      await this.refreshToken()
    }
    return this.tokenData!.accessToken
  }

  /**
   * Initiates the OAuth2 Authorization Code + PKCE flow.
   * Emits an OAuthRequestEvent for the frontend to open the auth URL.
   * Returns a Promise resolved by handleCallback() when the user completes auth.
   */
  async authenticate(): Promise<TokenData> {
    if (this.isAuthenticated()) {
      return this.tokenData!
    }

    const state = oauth.generateRandomState()
    const codeVerifier = oauth.generateRandomCodeVerifier()
    const codeChallenge = await oauth.calculatePKCECodeChallenge(codeVerifier)

    this.pendingState = state
    this.pendingCodeVerifier = codeVerifier

    const authorizationUrl = new URL(this.as.authorization_endpoint!)
    authorizationUrl.searchParams.set('client_id', this.client.client_id)
    authorizationUrl.searchParams.set('redirect_uri', this.config.redirectUri)
    authorizationUrl.searchParams.set('response_type', 'code')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('code_challenge', codeChallenge)
    authorizationUrl.searchParams.set('code_challenge_method', 'S256')
    if (this.config.scope) {
      const scope = Array.isArray(this.config.scope) ? this.config.scope.join(' ') : this.config.scope
      authorizationUrl.searchParams.set('scope', scope)
    }

    this.interactor.sendEvent(
      new OAuthRequestEvent({
        authUrl: authorizationUrl.toString(),
        state,
        integrationName: this.integrationName,
      })
    )

    return new Promise((resolve, reject) => {
      this.pendingResolve = resolve
      this.pendingReject = reject
    })
  }

  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    if (event.integrationName !== this.integrationName) return
    if (event.state !== this.pendingState) {
      this.interactor.error('Invalid OAuth state')
      return
    }

    if (event.error) {
      const errorMessage =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`
      this.interactor.warn(errorMessage)
      if (this.pendingReject) {
        this.pendingReject(new Error(errorMessage))
        this.pendingReject = null
      }
      this.pendingResolve = null
      this.pendingState = null
      this.pendingCodeVerifier = null
      return
    }

    if (!event.code || !this.pendingCodeVerifier || !this.pendingState) {
      this.interactor.error('No pending OAuth flow or missing code')
      return
    }

    try {
      const callbackUrl = new URL(this.config.redirectUri)
      callbackUrl.searchParams.set('code', event.code)
      callbackUrl.searchParams.set('state', event.state)

      const params = oauth.validateAuthResponse(this.as, this.client, callbackUrl, this.pendingState)

      const response = await oauth.authorizationCodeGrantRequest(
        this.as,
        this.client,
        this.clientAuth,
        params,
        this.config.redirectUri,
        this.pendingCodeVerifier
      )

      const result = await oauth.processAuthorizationCodeResponse(this.as, this.client, response)

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
      if (this.pendingReject) {
        this.pendingReject(error)
        this.pendingReject = null
      }
    } finally {
      this.pendingState = null
      this.pendingCodeVerifier = null
    }
  }

  private async refreshToken(): Promise<void> {
    if (!this.tokenData?.refreshToken) {
      throw new Error('No refresh token available')
    }

    try {
      const response = await oauth.refreshTokenGrantRequest(
        this.as,
        this.client,
        this.clientAuth,
        this.tokenData.refreshToken
      )

      const result = await oauth.processRefreshTokenResponse(this.as, this.client, response)

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
    const projectConfig = userProjects?.[this.projectName]
    const integrationConfig = projectConfig?.integration?.[this.integrationName]
    const tokens = integrationConfig?.oauth2?.tokens

    this.interactor.debug(
      `[OAuth:${this.integrationName}] loadTokensFromStorage: projectName=${this.projectName}, hasProjects=${!!userProjects}, hasProjectConfig=${!!projectConfig}, hasIntegrationConfig=${!!integrationConfig}, hasTokens=${!!tokens}`
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
    if (!userConfig.projects[this.projectName]) userConfig.projects[this.projectName] = { integration: {} }
    if (!userConfig.projects[this.projectName]!.integration) userConfig.projects[this.projectName]!.integration = {}
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]) {
      userConfig.projects[this.projectName]!.integration![this.integrationName] = {}
    }
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2) {
      userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2 = {} as any
    }

    const oauth2Config = userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2!
    oauth2Config.tokens = {
      access_token: this.tokenData.accessToken,
      refresh_token: this.tokenData.refreshToken,
      expires_at: this.tokenData.expiresAt,
    } as OAuth2Tokens

    this.userService.save()
    this.interactor.debug(`Saved OAuth tokens to storage for ${this.integrationName}`)
  }

  private clearTokensFromStorage(): void {
    const oauth2Config =
      this.userService.config.projects?.[this.projectName]?.integration?.[this.integrationName]?.oauth2
    if (oauth2Config) {
      delete oauth2Config.tokens
      this.userService.save()
      this.interactor.debug(`Cleared OAuth tokens from storage for ${this.integrationName}`)
    }
  }
}
