import axios from 'axios'
import { Interactor } from '../../model'

export async function getMergeRequest(
  mergeRequestId: string,
  gitlabBaseUrl: string,
  gitlabApiToken: string,
  interactor: Interactor
): Promise<any> {
  try {
    interactor.displayText(`Fetching GitLab merge request ${mergeRequestId}...`)
    const [changes, discussions, notes] = await Promise.allSettled([
      await axios.get(`${gitlabBaseUrl}/merge_requests/${mergeRequestId}/changes`, {
        headers: {
          'PRIVATE-TOKEN': gitlabApiToken,
        },
      }),
      await axios.get(`${gitlabBaseUrl}/merge_requests/${mergeRequestId}/discussions`, {
        headers: {
          'PRIVATE-TOKEN': gitlabApiToken,
        },
      }),
      await axios.get(`${gitlabBaseUrl}/merge_requests/${mergeRequestId}/notes?sort=asc&order_by=updated_at`, {
        headers: {
          'PRIVATE-TOKEN': gitlabApiToken,
        },
      }),
    ])

    return {
      changes: changes.status === 'fulfilled' ? changes.value.data : changes.reason,
      discussions: discussions.status === 'fulfilled' ? discussions.value.data : discussions.reason,
      notes: notes.status === 'fulfilled' ? notes.value.data : notes.reason,
    }
  } catch (error: any) {
    interactor.warn(`Failed to retrieve GitLab merge request`)
    return `Failed to retrieve GitLab merge request with ID ${mergeRequestId}: ${error}`
  }
}
