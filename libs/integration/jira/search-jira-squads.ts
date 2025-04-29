import axios from 'axios'
import { Interactor } from '../../model'

interface JiraField {
  id: string
  name: string
  custom: boolean
  schema?: {
    type: string
    custom?: string
  }
}

interface JiraFieldContext {
  id: string
  name: string
}

interface JiraFieldOption {
  id: string
  value: string
  disabled: boolean
}

/**
 * Search for squads in Jira and return their IDs and names
 * @param searchTerm The search term to match against squad names
 * @param jiraBaseUrl Jira base URL
 * @param jiraApiToken Jira API token
 * @param jiraUsername Jira username
 * @param interactor Interactor for displaying messages
 * @returns An array of squad objects with id and name properties
 */
export async function searchJiraSquads(
  searchTerm: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<{ squadCustomFieldId: string; squads: Array<{ id: string; name: string }> }> {
  try {
    interactor.displayText(`Searching for squads matching "${searchTerm}"...`)

    // Step 1: Fetch all available fields to find the Squad field
    const fieldsResponse = await axios.get(`${jiraBaseUrl}/rest/api/3/field`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    // Find the field with name exactly equal to "Squad"
    const squadField = (fieldsResponse.data as JiraField[]).find((field) => field.name === 'Squad')

    if (!squadField) {
      interactor.warn('Could not find a field named "Squad" in Jira')
      return { squadCustomFieldId: null, squads: [] }
    }

    const fieldId = squadField.id
    interactor.displayText(`Found Squad field with ID: ${fieldId}`)

    // Step 2: Retrieve the available context for this field
    const contextResponse = await axios.get(`${jiraBaseUrl}/rest/api/3/field/${fieldId}/context`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (!contextResponse.data.values || contextResponse.data.values.length === 0) {
      interactor.warn(`No contexts found for Squad field (${fieldId})`)
      return { squadCustomFieldId: fieldId, squads: [] }
    }

    // Get the first context ID (assuming there's typically one context for the Squad field)
    const contextId = contextResponse.data.values[0].id
    interactor.displayText(`Using context ID: ${contextId} for Squad field`)

    // Step 3: Get the options for the field in this context
    const optionsResponse = await axios.get(`${jiraBaseUrl}/rest/api/3/field/${fieldId}/context/${contextId}/option`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (!optionsResponse.data.values || optionsResponse.data.values.length === 0) {
      interactor.warn(`No options found for Squad field (${fieldId}) in context (${contextId})`)
      return { squadCustomFieldId: fieldId, squads: [] }
    }

    // Filter options based on search term
    const matchingSquads = (optionsResponse.data.values as JiraFieldOption[])
      .filter((option) => option.value?.toLowerCase().includes(searchTerm?.toLowerCase()))
      .map((option) => ({
        id: option.id,
        name: option.value,
      }))

    if (matchingSquads.length === 0) {
      interactor.warn(`No squads found matching "${searchTerm}". Please try a different search term.`)
    } else {
      interactor.displayText(`Found ${matchingSquads.length} squad(s) matching "${searchTerm}".`)
    }

    return { squadCustomFieldId: fieldId, squads: matchingSquads }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.error(`Error searching for squads: ${errorMessage}`)
    return { squadCustomFieldId: null, squads: [] }
  }
}
