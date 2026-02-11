/**
 * OAuth2 handler for HTTP integrations
 * Similar to BasecampOAuth but more generic
 */

import { Interactor, OAuthCallbackEvent, OAuth2Tokens } from '@coday/model'
import { UserService } from '@coday/service'
import { HttpOAuth2Auth } from './http-config'

export class HttpOAuth {
  private tokens: OAuth2Tokens | null = null

  constructor(
    private readonly clientId: string,
    private readonly clientSecret: string,
    private readonly redirectUri: string,
    private readonly authConfig: HttpOAuth2Auth,
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectName: string,
    private readonly integrationName: string
  ) {}

  /**
   * Initialize OAuth - load existing tokens or prompt for authorization
   */
  async initialize(): Promise<void> {
    // Try to load existing tokens from user config
    const userConfig = await this.userService.getConfig(this.projectName)
    const integrationConfig = userConfig.integration?.[this.integrationName]

    if (integrationConfig?.oauth2?.tokens) {
      this.tokens = integrationConfig.oauth2.tokens

      // Check if token is expired
      if (this.isTokenExpired()) {
        this.interactor.displayText(`🔄 OAuth token expired for ${this.integrationName}, refreshing...`)
        await this.refreshAccessToken()
      } else {
        this.interactor.displayText(`✅ Using existing OAuth token for ${this.integrationName}`)
      }
    } else {
      // No tokens, need to authorize
      await this.promptForAuthorization()
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
   * Prompt user to authorize
   */
  private async promptForAuthorization(): Promise<void> {
    const provider = this.authConfig.provider || 'standard'
    const authUrl = this.buildAuthorizationUrl()

    this.interactor.displayText(`
🔐 **OAuth Authorization Required for ${this.integrationName}**

Please authorize this application by visiting:
${authUrl}

After authorization, you'll be redirected and the token will be saved automatically.
`)

    // The actual callback will be handled by handleCallback()
    throw new Error('OAuth authorization required - waiting for callback')
  }

  /**
   * Build authorization URL
   */
  private buildAuthorizationUrl(): string {
    const authEndpoint = this.authConfig.authorizationEndpoint
    if (!authEndpoint) {
      throw new Error('Authorization endpoint not configured')
    }

    const params = new URLSearchParams({
      client_id: this.clientId,
      redirect_uri: this.redirectUri,
      response_type: 'code',
      scope: this.authConfig.scope || '',
    })

    return `${authEndpoint}?${params.toString()}`
  }

  /**
   * Handle OAuth callback
   */
  async handleCallback(event: OAuthCallbackEvent): Promise<void> {
    this.interactor.displayText(`📥 Received OAuth callback for ${this.integrationName}`)

    if (event.error) {
      throw new Error(`OAuth error: ${event.error}`)
    }

    if (!event.code) {
      throw new Error('No authorization code received')
    }

    // Exchange code for tokens
    await this.exchangeCodeForToken(event.code)

    this.interactor.displayText(`✅ OAuth authorization successful for ${this.integrationName}`)
  }

  /**
   * Exchange authorization code for access token
   */
  private async exchangeCodeForToken(code: string): Promise<void> {
    const tokenEndpoint = this.authConfig.tokenEndpoint
    if (!tokenEndpoint) {
      throw new Error('Token endpoint not configured')
    }

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      client_id: this.clientId,
      client_secret: this.clientSecret,
      redirect_uri: this.redirectUri,
    })

    const response = await fetch(tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })

    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`Token exchange failed: ${response.status} ${errorText}`)
    }

    const data = await response.json()

    // Calculate expiration timestamp
    const expiresIn = data.expires_in || 3600 // Default 1 hour
    const expiresAt = Date.now() + expiresIn * 1000

    this.tokens = {
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      expires_at: expiresAt,
      token_type: data.token_type || 'Bearer',
      scope: data.scope,
    }

    // Save tokens to user config
    await this.saveTokens()
  }

  /**
   * Refresh access token using refresh token
   */
  private async refreshAccessToken(): Promise<void> {
    if (!this.tokens?.refresh_token) {
      // No refresh token, need to re-authorize
      await this.promptForAuthorization()
      return
    }

    const tokenEndpoint = this.authConfig.tokenEndpoint
    if (!tokenEndpoint) {
      throw new Error('Token endpoint not configured')
    }

    const body = new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: this.tokens.refresh_token,
      client_id: this.clientId,
      client_secret: this.clientSecret,
    })

    try {
      const response = await fetch(tokenEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      })

      if (!response.ok) {
        throw new Error(`Token refresh failed: ${response.status}`)
      }

      const data = await response.json()

      const expiresIn = data.expires_in || 3600
      const expiresAt = Date.now() + expiresIn * 1000

      this.tokens = {
        access_token: data.access_token,
        refresh_token: data.refresh_token || this.tokens.refresh_token, // Keep old if not provided
        expires_at: expiresAt,
        token_type: data.token_type || 'Bearer',
        scope: data.scope,
      }

      await this.saveTokens()
      this.interactor.displayText(`✅ OAuth token refreshed for ${this.integrationName}`)
    } catch (error) {
      this.interactor.error(`Failed to refresh token: ${error}`)
      // Clear invalid tokens and prompt for re-authorization
      this.tokens = null
      await this.promptForAuthorization()
    }
  }

  /**
   * Save tokens to user config
   */
  private async saveTokens(): Promise<void> {
    const userConfig = await this.userService.getConfig(this.projectName)

    if (!userConfig.integration) {
      userConfig.integration = {}
    }

    if (!userConfig.integration[this.integrationName]) {
      userConfig.integration[this.integrationName] = {}
    }

    if (!userConfig.integration[this.integrationName].oauth2) {
      userConfig.integration[this.integrationName].oauth2 = {
        client_id: this.clientId,
        client_secret: this.clientSecret,
        redirect_uri: this.redirectUri,
      }
    }

    userConfig.integration[this.integrationName].oauth2!.tokens = this.tokens!

    await this.userService.saveConfig(userConfig, this.projectName)
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

    return this.tokens!.access_token
  }

  /**
   * Check if authenticated
   */
  isAuthenticated(): boolean {
    return this.tokens !== null && !this.isTokenExpired()
  }
}
