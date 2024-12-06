import axios from 'axios'
import { IntegrationConfig, Interactor } from '../../model'

type ListIssuesInput = {
  criteria: string
  integration: IntegrationConfig
  interactor: Interactor
}

export async function listIssues({ criteria, integration, interactor }: ListIssuesInput): Promise<any> {
  try {
    interactor.displayText(`Fetching Gitlab issues with criteria ${criteria}`)
    const suffix = criteria ? `?${criteria}` : ''
    const response = await axios.get(`${integration.apiUrl}/issues${suffix}`, {
      headers: {
        'PRIVATE-TOKEN': integration.apiKey,
      },
    })
    interactor.displayText('...GitLab response received.')

    return response.data
  } catch (error: any) {
    interactor.warn(`Failed to retrieve GitLab issues`)
    return `Failed to retrieve GitLab issues with criteria ${criteria}: ${error}`
  }
}
