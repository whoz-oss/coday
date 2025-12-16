import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { Interactor } from '../../model'
import { IntegrationService } from '../../service/integration.service'
import { BasecampOAuth } from './basecamp-oauth'
import { listBasecampProjects } from './list-projects'
import { OAuthCallbackEvent } from '@coday/coday-events'

export class BasecampTools extends AssistantToolFactory {
  name = 'BASECAMP'
  private oauth: BasecampOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService
  ) {
    super(interactor)
  }

  /**
   * Appelé par le système quand un OAuthCallbackEvent est reçu
   */
  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (this.oauth) {
      await this.oauth.handleCallback(event)
    }
  }

  protected async buildTools(): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!this.integrationService.hasIntegration(this.name)) {
      console.log('no basecamp')
      return result
    }

    const config = this.integrationService.getIntegration(this.name)
    if (!config) return result

    // Pour OAuth, on utilise apiUrl comme clientId et apiKey comme clientSecret
    const clientId = config.apiUrl
    const clientSecret = config.apiKey

    if (!clientId || !clientSecret) {
      this.interactor.displayText('Basecamp integration requires clientId (apiUrl) and clientSecret (apiKey)')
      return result
    }

    // Le redirect URI peut être configuré via config.username, sinon utiliser la valeur par défaut
    // Note: En développement avec proxy, utiliser localhost:3001 (pas 4200)
    const redirectUri = config.username || 'http://localhost:3001/oauth/callback'

    // Créer l'instance OAuth
    this.oauth = new BasecampOAuth(clientId, clientSecret, redirectUri, this.interactor)

    // Tool pour lister les projets
    const listProjectsTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'listBasecampProjects',
        description:
          'List all projects in the connected Basecamp account. Will prompt for OAuth authentication if not already connected.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: async () => listBasecampProjects(this.oauth!),
      },
    }

    result.push(listProjectsTool)

    return result
  }
}
