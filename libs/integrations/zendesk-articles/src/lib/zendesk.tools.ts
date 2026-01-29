import { retrieveZendeskArticle } from './retrieve-zendesk-article'
import { searchZendeskArticles } from './search-zendesk-articles'
import { IntegrationService } from '@coday/service'
import { Interactor } from '@coday/model'
import { AssistantToolFactory } from '@coday/model'
import { CodayTool } from '@coday/model'
import { FunctionTool } from '@coday/model'

export class ZendeskTools extends AssistantToolFactory {
  static readonly TYPE = 'ZENDESK' as const

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    instanceName: string,
    config?: any
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    if (!this.integrationService.hasIntegration(this.name)) {
      return result
    }

    const zendeskSubdomain = this.integrationService.getApiUrl(this.name)
    const zendeskEmail = this.integrationService.getUsername(this.name)
    const zendeskApiToken = this.integrationService.getApiKey(this.name)
    if (!(zendeskSubdomain && zendeskEmail && zendeskApiToken)) {
      return result
    }

    const articleRetrievalFunction: FunctionTool<{ articleId: string; locale?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}_getArticle`,
        description:
          'Retrieve a Zendesk Help Center article by article ID. Returns the full article content including HTML body.',
        parameters: {
          type: 'object',
          properties: {
            articleId: {
              type: 'string',
              description: 'Zendesk article ID (numeric)',
            },
            locale: {
              type: 'string',
              description:
                'Optional locale code (e.g., "en-us", "fr"). If not provided, returns article in default locale.',
            },
          },
        },
        parse: JSON.parse,
        function: (params: { articleId: string; locale?: string }) =>
          retrieveZendeskArticle(
            params.articleId,
            zendeskSubdomain,
            zendeskEmail,
            zendeskApiToken,
            this.interactor,
            params.locale
          ),
      },
    }

    const searchFunction: FunctionTool<{ query: string; locale?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}_searchArticles`,
        description:
          'Search Zendesk Help Center articles by query text. Returns a list of matching articles with ID, title, snippet, and URL. If several articles seem relevant, you **should** retrieve them using retrieveZendeskArticle. Keep queries simple and focused for best results.',
        parameters: {
          type: 'object',
          properties: {
            query: {
              type: 'string',
              description:
                'Search query text. Can include multiple words. Zendesk will search across article titles and content.',
            },
            locale: {
              type: 'string',
              description:
                'Optional locale code to search in (e.g., "en-us", "fr"). If not provided, searches in default locale. Use "*" to search across all locales.',
            },
          },
        },
        parse: JSON.parse,
        function: (params: { query: string; locale?: string }) => {
          return searchZendeskArticles(
            params.query,
            zendeskSubdomain,
            zendeskEmail,
            zendeskApiToken,
            this.interactor,
            params.locale
          )
        },
      },
    }

    result.push(articleRetrievalFunction, searchFunction)

    return result
  }
}
