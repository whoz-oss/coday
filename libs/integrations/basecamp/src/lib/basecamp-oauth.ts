/**
 * Basecamp OAuth2 Integration
 *
 * This implementation uses oauth4webapi (low-level OAuth library) instead of openid-client because:
 * - Basecamp's OAuth2 implementation has non-standard quirks:
 *   - Missing token_type in token response (OAuth2 spec violation)
 *   - Requires custom 'type' parameter (type=web_server, type=refresh)
 *   - Non-standard accounts endpoint for multi-account selection
 * - oauth4webapi's low-level API allows manual fallbacks for these quirks
 * - Recommended by oauth.net (linked in Basecamp API docs)
 * - Zero dependencies = better security posture
 * - Same author (Filip Skokan), same certification (OpenID Connect) as openid-client
 *
 * See: https://github.com/basecamp/api/blob/master/sections/authentication.md
 */
import * as oauth from 'oauth4webapi'
import { Interactor } from '@coday/model/interactor'
import { OAuthRequestEvent, OAuthCallbackEvent } from '@coday/model/coday-events'
import { UserService } from '@coday/service'
import { OAuth2Tokens } from '@coday/model/integration-config'

export interface BasecampAccount {
  id: number
  name: string
  href: string
}

export interface TokenData {
  accessToken: string
  refreshToken?: string
  expiresAt: number
}

export class BasecampOAuth {
  // Configuration oauth4webapi
  private as: oauth.AuthorizationServer
  private client: oauth.Client
  private clientAuth: oauth.ClientAuth

  private tokenData: TokenData | null = null
  private accounts: BasecampAccount[] = []
  private selectedAccountHref: string | null = null

  private pendingState: string | null = null
  private pendingCodeVerifier: string | null = null
  private pendingResolve: ((token: TokenData) => void) | null = null
  private pendingReject: ((error: Error) => void) | null = null

  constructor(
    clientId: string,
    clientSecret: string,
    private redirectUri: string,
    private interactor: Interactor,
    private userService: UserService,
    private projectName: string,
    private integrationName: string = 'BASECAMP'
  ) {
    // Basecamp has no OIDC discovery endpoint
    this.as = {
      issuer: 'https://launchpad.37signals.com',
      authorization_endpoint: 'https://launchpad.37signals.com/authorization/new',
      token_endpoint: 'https://launchpad.37signals.com/authorization/token',
    }

    this.client = { client_id: clientId }
    this.clientAuth = oauth.ClientSecretPost(clientSecret)
  }

  isAuthenticated(): boolean {
    if (!this.tokenData) {
      // Try to load tokens from user storage
      this.loadTokensFromStorage()
    }
    if (!this.tokenData) return false
    return this.tokenData.expiresAt > Date.now() + 5 * 60 * 1000
  }

  async getAccessToken(): Promise<string> {
    if (!this.tokenData) {
      throw new Error('Not authenticated. Call authenticate() first.')
    }

    if (!this.isAuthenticated() && this.tokenData.refreshToken) {
      await this.refreshToken()
    }

    return this.tokenData.accessToken
  }

  getApiBaseUrl(): string {
    if (!this.selectedAccountHref) {
      throw new Error('No account selected')
    }
    return this.selectedAccountHref
  }

  async authenticate(): Promise<TokenData> {
    if (this.isAuthenticated()) {
      return this.tokenData!
    }

    // Generate PKCE challenge and state
    const state = oauth.generateRandomState()
    const codeVerifier = oauth.generateRandomCodeVerifier()
    const codeChallenge = await oauth.calculatePKCECodeChallenge(codeVerifier)

    // Store state and verifier for callback validation
    this.pendingState = state
    this.pendingCodeVerifier = codeVerifier

    const authorizationUrl = new URL(this.as.authorization_endpoint!)
    authorizationUrl.searchParams.set('client_id', this.client.client_id)
    authorizationUrl.searchParams.set('redirect_uri', this.redirectUri)
    authorizationUrl.searchParams.set('response_type', 'code')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('code_challenge', codeChallenge)
    authorizationUrl.searchParams.set('code_challenge_method', 'S256')
    authorizationUrl.searchParams.set('type', 'web_server')

    this.interactor.sendEvent(
      new OAuthRequestEvent({
        authUrl: authorizationUrl.toString(),
        state: state,
        integrationName: 'BASECAMP',
      })
    )

    // Return promise that will be resolved by handleCallback()
    // Note: timeout is handled by frontend
    return new Promise((resolve, reject) => {
      this.pendingResolve = resolve
      this.pendingReject = reject
    })
  }

  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    if (event.integrationName !== 'BASECAMP') return
    if (event.state !== this.pendingState) {
      this.interactor.error('Invalid OAuth state')
      return
    }

    // Handle OAuth errors (access_denied, user_cancelled, etc.)
    if (event.error) {
      const errorMessage =
        event.error === 'access_denied'
          ? 'OAuth authentication denied by user'
          : event.error === 'user_cancelled'
            ? 'OAuth authentication cancelled by user'
            : `OAuth error: ${event.error}${event.errorDescription ? ' - ' + event.errorDescription : ''}`

      this.interactor.warn(errorMessage)

      // Reject the authentication promise
      if (this.pendingReject) {
        this.pendingReject(new Error(errorMessage))
        this.pendingReject = null
      }

      // Cleanup pending state
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
      const callbackUrl = new URL(this.redirectUri)
      callbackUrl.searchParams.set('code', event.code)
      callbackUrl.searchParams.set('state', event.state)

      const params = oauth.validateAuthResponse(this.as, this.client, callbackUrl, this.pendingState)

      // Exchange authorization code for access token
      let response = await oauth.authorizationCodeGrantRequest(
        this.as,
        this.client,
        this.clientAuth,
        params,
        this.redirectUri,
        this.pendingCodeVerifier,
        {
          additionalParameters: new URLSearchParams({ type: 'web_server' }),
        }
      )

      this.interactor.debug(`Token exchange response status: ${response.status}`)

      // Get raw response body before processing
      const rawBody = await response.text()

      // Recreate response with same body for oauth4webapi processing
      response = new Response(rawBody, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers,
      })

      // oauth4webapi validates strictly, but Basecamp omits token_type (OAuth2 spec violation)
      // We try standard validation first, then fall back to manual parsing
      let result: any
      try {
        result = await oauth.processAuthorizationCodeResponse(this.as, this.client, response)
        this.interactor.debug('oauth4webapi validation succeeded')
      } catch (error: any) {
        // Fallback: manual parsing for Basecamp's non-standard response
        this.interactor.debug(`Using manual parsing for Basecamp response: ${error.message}`)

        if (!response.ok) {
          throw new Error(`Token exchange failed: ${response.status}`)
        }

        result = JSON.parse(rawBody)
        this.interactor.debug('Token response parsed successfully')
      }

      // Store access and refresh tokens
      this.tokenData = {
        accessToken: result.access_token,
        refreshToken: result.refresh_token,
        expiresAt: Date.now() + (result.expires_in ?? 1209600) * 1000, // Default 14 days
      }

      // Fetch available Basecamp accounts before saving
      await this.fetchAccounts()

      this.saveTokensToStorage()

      // Resolve the authentication promise
      if (this.pendingResolve) {
        this.pendingResolve(this.tokenData)
        this.pendingResolve = null
      }

      // Cleanup pending state
      this.pendingState = null
      this.pendingCodeVerifier = null
    } catch (error: any) {
      this.interactor.error(`OAuth token exchange failed: ${error.message}`)
      this.pendingState = null
      this.pendingCodeVerifier = null
    }
  }

  private async fetchAccounts(): Promise<void> {
    if (!this.tokenData) return

    try {
      const response = await oauth.protectedResourceRequest(
        this.tokenData.accessToken,
        'GET',
        new URL('https://launchpad.37signals.com/authorization.json'),
        undefined,
        undefined
      )

      if (!response.ok) return

      const data = (await response.json()) as any

      // Filter for Basecamp 3 accounts only
      this.accounts = data.accounts
        .filter((a: any) => a.product === 'bc3')
        .map((a: any) => ({ id: a.id, name: a.name, href: a.href }))

      if (this.accounts.length > 0) {
        const firstAccount = this.accounts[0]
        if (firstAccount) {
          this.selectedAccountHref = firstAccount.href
          this.interactor.displayText(`Connected to Basecamp account: ${firstAccount.name}`)
        }
      }
    } catch (error: any) {
      this.interactor.warn(`Failed to fetch Basecamp accounts: ${error.message}`)
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
        this.tokenData.refreshToken,
        {
          additionalParameters: new URLSearchParams({ type: 'refresh' }),
        }
      )

      const result = await oauth.processRefreshTokenResponse(this.as, this.client, response)

      this.tokenData = {
        accessToken: result.access_token,
        refreshToken: result.refresh_token ?? this.tokenData.refreshToken,
        expiresAt: Date.now() + (result.expires_in ?? 1209600) * 1000, // Default 14 days
      }

      // Save refreshed tokens to storage
      this.saveTokensToStorage()
    } catch (error: any) {
      this.tokenData = null
      this.clearTokensFromStorage()
      throw new Error(`Token refresh failed: ${error.message}`)
    }
  }

  /**
   * Load tokens from UserService storage
   */
  private loadTokensFromStorage(): void {
    const userConfig = this.userService.config
    const oauth2 = userConfig.projects?.[this.projectName]?.integration?.[this.integrationName]?.oauth2
    const tokens = oauth2?.tokens
    const accountHref = oauth2?.account_href
    const accountName = oauth2?.account_name

    if (tokens) {
      this.tokenData = {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        expiresAt: tokens.expires_at,
      }

      if (accountHref) {
        this.selectedAccountHref = accountHref
      }

      this.interactor.debug(`Loaded OAuth tokens from storage for ${this.integrationName}`)

      // Log token expiration in debug
      const expiresAt = new Date(this.tokenData.expiresAt)
      const now = new Date()
      const remainingMs = this.tokenData.expiresAt - now.getTime()
      const remainingDays = Math.floor(remainingMs / (1000 * 60 * 60 * 24))
      const remainingHours = Math.floor((remainingMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))

      this.interactor.debug(`Token expires at: ${expiresAt.toLocaleString()} (in ${remainingDays}d ${remainingHours}h)`)

      if (accountName) {
        this.interactor.displayText(`Using stored Basecamp account: ${accountName}`)
      }
    }
  }

  /**
   * Save tokens to UserService storage
   */
  private saveTokensToStorage(): void {
    if (!this.tokenData) return

    const userConfig = this.userService.config

    // Ensure nested configuration structure exists
    if (!userConfig.projects) userConfig.projects = {}
    if (!userConfig.projects[this.projectName]) userConfig.projects[this.projectName] = { integration: {} }
    if (!userConfig.projects[this.projectName]!.integration) userConfig.projects[this.projectName]!.integration = {}
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]) {
      userConfig.projects[this.projectName]!.integration![this.integrationName] = {}
    }
    if (!userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2) {
      userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2 = {} as any
    }

    // Save tokens and selected account information
    const oauth2Config = userConfig.projects[this.projectName]!.integration![this.integrationName]!.oauth2!
    oauth2Config.tokens = {
      access_token: this.tokenData.accessToken,
      refresh_token: this.tokenData.refreshToken,
      expires_at: this.tokenData.expiresAt,
    } as OAuth2Tokens

    if (this.selectedAccountHref) {
      oauth2Config.account_href = this.selectedAccountHref
    }

    const selectedAccount = this.accounts.find((a) => a.href === this.selectedAccountHref)
    if (selectedAccount) {
      oauth2Config.account_name = selectedAccount.name
    }

    // Write to user configuration file
    this.userService.save()

    // Log token expiration in debug
    const expiresAt = new Date(this.tokenData.expiresAt)
    const now = new Date()
    const remainingMs = this.tokenData.expiresAt - now.getTime()
    const remainingDays = Math.floor(remainingMs / (1000 * 60 * 60 * 24))
    const remainingHours = Math.floor((remainingMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))

    this.interactor.debug(
      `Saved OAuth tokens to storage for ${this.integrationName}. Expires at: ${expiresAt.toLocaleString()} (in ${remainingDays}d ${remainingHours}h)`
    )
  }

  /**
   * Clear tokens from UserService storage
   */
  private clearTokensFromStorage(): void {
    const userConfig = this.userService.config
    const oauth2Config = userConfig.projects?.[this.projectName]?.integration?.[this.integrationName]?.oauth2

    if (oauth2Config) {
      delete oauth2Config.tokens
      delete oauth2Config.account_href
      delete oauth2Config.account_name
      this.userService.save()
      this.interactor.debug(`Cleared OAuth tokens from storage for ${this.integrationName}`)
    }
  }
}
