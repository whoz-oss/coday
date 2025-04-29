import { Interactor } from '../../model'
import { retrieveCustomFieldInfo, filterFieldOptionsBySearchTerm } from './jira-custom-fields'

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

    // Use the generic function to retrieve custom field info
    const { fieldId, options } = await retrieveCustomFieldInfo(
      'Squad',
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      interactor
    )

    if (!fieldId) {
      return { squadCustomFieldId: null, squads: [] }
    }

    // Filter options based on search term
    const matchingSquads = filterFieldOptionsBySearchTerm(options, searchTerm)

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