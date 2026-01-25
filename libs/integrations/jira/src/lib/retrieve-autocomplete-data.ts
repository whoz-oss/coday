import axios from 'axios'
import { Interactor } from '@coday/model/interactor'
import { AutocompleteDataResponse } from './jira'

export async function retrieveAutocompleteData(
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<AutocompleteDataResponse> {
  if (!jiraBaseUrl || !jiraApiToken || !jiraUsername) {
    throw new Error('Jira integration incorrectly set')
  }

  try {
    interactor.displayText(`Fetching JIRA autocomplete suggestions...`)
    const response: {
      data: AutocompleteDataResponse
    } = await axios.get(`${jiraBaseUrl}/rest/api/3/jql/autocompletedata`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (!response.data) {
      interactor.warn('Empty response from Jira API on autocomplete data')
    }

    interactor.displayText(`Successfully retrieved JIRA autocomplete suggestions`)
    return response.data || { visibleFieldNames: [] }
  } catch (error) {
    interactor.warn(`Could not fetch autocomplete data: ${error}`)
    return { visibleFieldNames: [] }
  }
}
