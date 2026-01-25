import { retrieveConfluencePage } from './retrieve-confluence-page'
import { searchConfluencePages } from './search-confluence-pages'
import { IntegrationService } from '@coday/service/integration.service'
import { Interactor } from '@coday/model/interactor'
import { AssistantToolFactory } from '@coday/integration/assistant-tool-factory'
import { CodayTool } from '@coday/model/coday-tool'
import { FunctionTool } from '@coday/model/integration-types'

export class ConfluenceTools extends AssistantToolFactory {
  name = 'CONFLUENCE'

  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected async buildTools(): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    if (!this.integrationService.hasIntegration('CONFLUENCE')) {
      return result
    }

    const confluenceBaseUrl = this.integrationService.getApiUrl('CONFLUENCE')
    const confluenceUsername = this.integrationService.getUsername('CONFLUENCE')
    const confluenceApiToken = this.integrationService.getApiKey('CONFLUENCE')
    if (!(confluenceBaseUrl && confluenceUsername && confluenceApiToken)) {
      return result
    }

    const pageRetrievalFunction: FunctionTool<{ pageId: string }> = {
      type: 'function',
      function: {
        name: 'retrieveConfluencePage',
        description: 'Retrieve Confluence wiki page by page ID.',
        parameters: {
          type: 'object',
          properties: {
            pageId: { type: 'string', description: 'Confluence page ID' },
          },
        },
        parse: JSON.parse,
        function: (params: { pageId: string }) =>
          retrieveConfluencePage(
            params.pageId,
            confluenceBaseUrl,
            confluenceApiToken,
            confluenceUsername,
            this.interactor
          ),
      },
    }

    const searchFunction: FunctionTool<{ query: string }> = {
      type: 'function',
      function: {
        name: 'searchConfluencePage',
        description:
          'Search Confluence pages by words, returns list of page matches. If several pages seem relevant, you **should** read them. Use several searches if many words to check.',
        parameters: {
          type: 'object',
          properties: {
            query: {
              type: 'string',
              description:
                'Search words, can be in any order, the query will be split and joined by an AND condition for text search. Keep word count preferably low (1 or 2) to avoid too restrictive search. ',
            },
          },
        },
        parse: JSON.parse,
        function: (params: { query: string }) => {
          return searchConfluencePages(
            params.query,
            confluenceBaseUrl,
            confluenceApiToken,
            confluenceUsername,
            this.interactor
          )
        },
      },
    }

    result.push(pageRetrievalFunction, searchFunction)

    return result
  }
}
