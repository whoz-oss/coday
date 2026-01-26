import axios from 'axios'
import { Interactor } from '@coday/model'

export async function addGlobalComment({
  mergeRequestId,
  comment,
  gitlabBaseUrl,
  gitlabApiToken,
  interactor,
}: {
  mergeRequestId: string
  comment: string
  gitlabBaseUrl: string
  gitlabApiToken: string
  interactor: Interactor
}): Promise<string> {
  const headers = { 'PRIVATE-TOKEN': gitlabApiToken }
  // gitlabBaseUrl already contains the project path from integration setup
  const url = `${gitlabBaseUrl}/merge_requests/${mergeRequestId}/notes`

  try {
    interactor.displayText('Adding note to MR...')
    const response = await axios.post(url, { body: comment }, { headers })
    interactor.displayText('Note added successfully')
    return JSON.stringify(response.data)
  } catch (error: any) {
    const errorMessage = error.response
      ? `GitLab API error: ${error.response.status} - ${JSON.stringify(error.response.data)}`
      : `Network error: ${error.message}`
    interactor.error(errorMessage)
    throw new Error(errorMessage)
  }
}
