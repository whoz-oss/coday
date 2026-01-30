import axios from 'axios'
import { Interactor } from '@coday/model'

/**
 * Interfaces for Jira field data structures
 */
export interface JiraCustomField {
  id: string
  name: string
  custom: boolean
  schema?: {
    type: string
    custom?: string
  }
}

export interface JiraFieldContext {
  id: string
  name: string
}

export interface JiraFieldOption {
  id: string
  value: string
  disabled: boolean
}

export interface JiraFieldOptionsResponse {
  values: JiraFieldOption[]
}

export interface JiraFieldContextResponse {
  values: JiraFieldContext[]
}

/**
 * Retrieves all available fields from Jira
 * @param jiraBaseUrl Jira base URL
 * @param jiraApiToken Jira API token
 * @param jiraUsername Jira username
 * @param interactor Interactor for displaying messages
 * @returns Array of Jira fields
 */
export async function retrieveAllJiraFields(
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<JiraCustomField[]> {
  try {
    interactor.displayText('Retrieving all Jira fields...')

    const response = await axios.get(`${jiraBaseUrl}/rest/api/3/field`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    interactor.displayText(`Successfully retrieved ${response.data.length} Jira fields`)
    return response.data as JiraCustomField[]
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.error(`Error retrieving Jira fields: ${errorMessage}`)
    return []
  }
}

/**
 * Finds a specific Jira field by exact name
 * @param fieldName The exact name of the field to find
 * @param fields Array of Jira fields to search through
 * @param interactor Interactor for displaying messages
 * @returns The found field or null if not found
 */
export function findJiraFieldByName(
  fieldName: string,
  fields: JiraCustomField[],
  interactor: Interactor
): JiraCustomField | null {
  const field = fields.find((field) => field.name === fieldName)

  if (!field) {
    interactor.warn(`Could not find a field named "${fieldName}" in Jira`)
    return null
  }

  interactor.displayText(`Found field "${fieldName}" with ID: ${field.id}`)
  return field
}

/**
 * Retrieves available contexts for a specific Jira field
 * @param fieldId The ID of the field
 * @param jiraBaseUrl Jira base URL
 * @param jiraApiToken Jira API token
 * @param jiraUsername Jira username
 * @param interactor Interactor for displaying messages
 * @returns Array of field contexts
 */
export async function retrieveFieldContexts(
  fieldId: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<JiraFieldContext[]> {
  try {
    interactor.displayText(`Retrieving contexts for field ID: ${fieldId}...`)

    const response = await axios.get(`${jiraBaseUrl}/rest/api/3/field/${fieldId}/context`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (!response.data.values || response.data.values.length === 0) {
      interactor.warn(`No contexts found for field (${fieldId})`)
      return []
    }

    interactor.displayText(`Found ${response.data.values.length} context(s) for field ID: ${fieldId}`)
    return response.data.values as JiraFieldContext[]
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.error(`Error retrieving field contexts: ${errorMessage}`)
    return []
  }
}

/**
 * Retrieves options for a specific field in a specific context
 * @param fieldId The ID of the field
 * @param contextId The ID of the context
 * @param jiraBaseUrl Jira base URL
 * @param jiraApiToken Jira API token
 * @param jiraUsername Jira username
 * @param interactor Interactor for displaying messages
 * @returns Array of field options
 */
export async function retrieveFieldOptions(
  fieldId: string,
  contextId: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<JiraFieldOption[]> {
  try {
    interactor.displayText(`Retrieving options for field ID: ${fieldId} in context ID: ${contextId}...`)

    const response = await axios.get(`${jiraBaseUrl}/rest/api/3/field/${fieldId}/context/${contextId}/option`, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (!response.data.values || response.data.values.length === 0) {
      interactor.warn(`No options found for field (${fieldId}) in context (${contextId})`)
      return []
    }

    interactor.displayText(`Found ${response.data.values.length} option(s) for field ID: ${fieldId}`)
    return response.data.values as JiraFieldOption[]
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.error(`Error retrieving field options: ${errorMessage}`)
    return []
  }
}

/**
 * Retrieves all information about a custom field, including its options
 * @param fieldName The exact name of the field to find
 * @param jiraBaseUrl Jira base URL
 * @param jiraApiToken Jira API token
 * @param jiraUsername Jira username
 * @param interactor Interactor for displaying messages
 * @returns Object containing field information and its options
 */
export async function retrieveCustomFieldInfo(
  fieldName: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<{
  fieldId: string | null
  field: JiraCustomField | null
  contexts: JiraFieldContext[]
  options: JiraFieldOption[]
}> {
  try {
    // Step 1: Retrieve all fields
    const allFields = await retrieveAllJiraFields(jiraBaseUrl, jiraApiToken, jiraUsername, interactor)

    // Step 2: Find the specific field
    const field = findJiraFieldByName(fieldName, allFields, interactor)
    if (!field) {
      return { fieldId: null, field: null, contexts: [], options: [] }
    }

    // Step 3: Get contexts for the field
    const contexts = await retrieveFieldContexts(field.id, jiraBaseUrl, jiraApiToken, jiraUsername, interactor)
    if (contexts.length === 0) {
      return { fieldId: field.id, field, contexts: [], options: [] }
    }

    // Step 4: Get options for the first context
    // Note: We're using the first context by default, but this could be parameterized if needed
    const contextId = contexts[0]!.id
    const options = await retrieveFieldOptions(field.id, contextId, jiraBaseUrl, jiraApiToken, jiraUsername, interactor)

    return {
      fieldId: field.id,
      field,
      contexts,
      options,
    }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error)
    interactor.error(`Error retrieving custom field info: ${errorMessage}`)
    return { fieldId: null, field: null, contexts: [], options: [] }
  }
}

/**
 * Filters options for a specific field based on a search term
 * @param options Array of field options
 * @param searchTerm Term to search for in option values
 * @returns Filtered array of options with just id and name
 */
export function filterFieldOptionsBySearchTerm(
  options: JiraFieldOption[],
  searchTerm: string
): Array<{ id: string; name: string }> {
  return options
    .filter((option) => option.value?.toLowerCase().includes(searchTerm?.toLowerCase()))
    .map((option) => ({
      id: option.id,
      name: option.value,
    }))
}
