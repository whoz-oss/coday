import { retrieveConfluencePage } from './retrieve-confluence-page'
import { searchConfluencePages } from './search-confluence-pages'
import { integrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'

export class ConfluenceTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }

  protected buildTools(context: CommandContext): CodayTool[] {
    const result: CodayTool[] = []
    if (!integrationService.hasIntegration('CONFLUENCE')) {
      return result
    }

    const confluenceBaseUrl = integrationService.getApiUrl('CONFLUENCE')
    const confluenceUsername = integrationService.getUsername('CONFLUENCE')
    const confluenceApiToken = integrationService.getApiKey('CONFLUENCE')
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
