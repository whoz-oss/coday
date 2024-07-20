import axios from "axios"
import {IntegrationConfig, Interactor} from "../../model"

type ListMergeRequestsInput = {
  criteria: string,
  integration: IntegrationConfig,
  interactor: Interactor
}

export async function listMergeRequests({criteria, integration, interactor}: ListMergeRequestsInput): Promise<any> {
  
  try {
    interactor.displayText(`Fetching Gitlab merge requests with criteria ${criteria}`)
    const suffix = criteria ? `?${criteria}` : ""
    const response = await axios.get(
      `${integration.apiUrl}/merge_requests${suffix}`,
      {
        headers: {
          "PRIVATE-TOKEN": integration.apiKey
        }
      }
    )
    interactor.displayText("...GitLab response received.")
    
    return response.data
  } catch (error: any) {
    interactor.warn(`Failed to retrieve GitLab merge requests`)
    return `Failed to retrieve GitLab merge requests with criteria ${criteria}: ${error}`
  }
}