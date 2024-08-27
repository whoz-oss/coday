import {retrieveConfluencePage} from "./retrieve-confluence-page"
import {searchConfluencePages} from "./search-confluence-pages"
import {integrationService} from "../../service/integration.service"
import {CommandContext, IntegrationName, Interactor} from "../../model"
import {AssistantToolFactory, Tool} from "../assistant-tool-factory"
import {FunctionTool} from "../types"

export class ConfluenceTools extends AssistantToolFactory {
  
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    if (!integrationService.hasIntegration(IntegrationName.CONFLUENCE)) {
      return result
    }
    
    const confluenceBaseUrl = integrationService.getApiUrl(IntegrationName.CONFLUENCE)
    const confluenceUsername = integrationService.getUsername(IntegrationName.CONFLUENCE)
    const confluenceApiToken = integrationService.getApiKey(IntegrationName.CONFLUENCE)
    if (!(confluenceBaseUrl && confluenceUsername && confluenceApiToken)) {
      return result
    }
    
    const pageRetrievalFunction: FunctionTool<{ pageId: string }> = {
      type: "function",
      function: {
        name: "retrieveConfluencePage",
        description: "Retrieve Confluence wiki page by page ID.",
        parameters: {
          type: "object",
          properties: {
            pageId: {type: "string", description: "Confluence page ID"},
          }
        },
        parse: JSON.parse,
        function: (params: { pageId: string }) =>
          retrieveConfluencePage(params.pageId, confluenceBaseUrl, confluenceApiToken, confluenceUsername, this.interactor)
      }
    }
    
    const searchFunction: FunctionTool<{ query: string }> = {
      type: "function",
      function: {
        name: "searchConfluencePage",
        description: "Search Confluence pages by text, returns list of page matches. If several pages seem relevant, you **should** read them.",
        parameters: {
          type: "object",
          properties: {
            query: {
              type: "string",
              description: "Search words, can be in any order, the query will be split and joined by an AND condition for text search. Keep word count preferably low (1 to 4) to avoid too restrictive search."
            },
          }
        },
        parse: JSON.parse,
        function: (params: { query: string }) => {
          console.log("toto")
          return searchConfluencePages(params.query, confluenceBaseUrl, confluenceApiToken, confluenceUsername, this.interactor)
        }
      }
    }
    
    result.push(pageRetrievalFunction, searchFunction)
    
    return result
  }
}
