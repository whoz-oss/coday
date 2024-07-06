import axios from 'axios'
import {Interactor} from "../../model/interactor"

export async function getMergeRequest(mergeRequestId: string, gitlabBaseUrl: string, gitlabApiToken: string, gitlabUsername: string, interactor: Interactor): Promise<any> {

  if (!gitlabBaseUrl || !gitlabApiToken || !gitlabUsername) {
    throw new Error('GitLab integration incorrectly set')
  }

  try {
    interactor.displayText(`Fetching GitLab merge request ${mergeRequestId}...`)
    const response = await axios.get(
      `${gitlabBaseUrl}/merge_requests/${mergeRequestId}/changes`,
      {
        headers: {
          'PRIVATE-TOKEN': gitlabApiToken
        }
      }
    )
    interactor.displayText("...GitLab response received.")

    return response.data
  } catch (error: any) {
    interactor.warn(`Failed to retrieve GitLab merge request`)
    return `Failed to retrieve GitLab merge request with ID ${mergeRequestId}: ${error}`
  }
}
