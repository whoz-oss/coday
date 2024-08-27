import axios from "axios"
import {Interactor} from "../../model"

export async function searchConfluencePages(query: string, confluenceBaseUrl: string, confluenceApiToken: string, confluenceUsername: string, interactor: Interactor): Promise<any> {
  
  if (!confluenceBaseUrl || !confluenceApiToken || !confluenceUsername) {
    throw new Error("Confluence integration incorrectly set")
  }
  
  try {
    interactor.displayText(`Searching Confluence for query: "${query}"...`)
    const words = query.split(" ")
    const queryText = [...words.map(w => `text ~ ${w}`), "type = page"].join(" AND ")
    const url = `${confluenceBaseUrl}/wiki/rest/api/search?cql=${encodeURIComponent(queryText)}&limit=10&expand=body.editor2`
    const response = await axios.get(
      url,
      {
        auth: {
          username: confluenceUsername,
          password: confluenceApiToken,
        },
      }
    )
    interactor.displayText(`... received search results from Confluence.`)
    
    // Confluence search api returns all matching text with some highlight marks that made my eyes bleed.
    const stringified = JSON.stringify(response.data.results).replace(/@@@hl@@@|@@@endhl@@@/g, "")
    return JSON.parse(stringified)
  } catch (error: any) {
    interactor.warn(`Failed to search Confluence content`)
    return `Failed to perform search: "${query}": ${error.message}`
  }
}
