import axios from "axios"
import {Interactor} from "../../model"

export async function retrieveConfluencePage(pageId: string, confluenceBaseUrl: string, confluenceApiToken: string, confluenceUsername: string, interactor: Interactor): Promise<any> {
  
  if (!confluenceBaseUrl || !confluenceApiToken || !confluenceUsername) {
    throw new Error("Confluence integration incorrectly set")
  }
  
  try {
    interactor.displayText(`Fetching Confluence page ${pageId}...`)
    const response = await axios.get(
      `${confluenceBaseUrl}/wiki/api/v2/pages/${pageId}?body-format=view`,
      {
        auth: {
          username: confluenceUsername,
          password: confluenceApiToken,
        },
      }
    )
    interactor.displayText(`... got Confluence response.`)
    return response.data
  } catch (error: any) {
    interactor.warn(`Failed to retrieve Confluence page`)
    return `Failed to retrieve Confluence page with ID ${pageId}: ${error.message}`
  }
}
