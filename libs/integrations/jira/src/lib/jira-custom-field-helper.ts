import { Interactor } from '@coday/model/interactor'
import {
  JiraCustomField,
  retrieveAllJiraFields,
  retrieveFieldContexts,
  retrieveFieldOptions,
} from './jira-custom-fields'

/**
 * Utility class for working with Jira custom fields - designed to be agnostic to specific field IDs or names
 */
export class JiraCustomFieldHelper {
  /**
   * Retrieve all field options for a given field ID
   * @param fieldId The ID of the field (e.g., customfield_10582)
   * @param jiraBaseUrl Jira base URL
   * @param jiraApiToken Jira API token
   * @param jiraUsername Jira username
   * @param interactor Interactor for displaying messages
   * @returns Array of option objects with id and name
   */
  public static async getFieldOptionsById(
    fieldId: string,
    jiraBaseUrl: string,
    jiraApiToken: string,
    jiraUsername: string,
    interactor: Interactor
  ): Promise<Array<{ id: string; name: string }>> {
    try {
      // Get contexts for the field
      const contexts = await retrieveFieldContexts(fieldId, jiraBaseUrl, jiraApiToken, jiraUsername, interactor)

      if (!contexts || contexts.length === 0) {
        interactor.warn(`No contexts found for field ${fieldId}`)
        return []
      }

      // Get options for the first context
      const contextId = contexts[0]!.id
      const options = await retrieveFieldOptions(
        fieldId,
        contextId,
        jiraBaseUrl,
        jiraApiToken,
        jiraUsername,
        interactor
      )

      if (!options || options.length === 0) {
        interactor.warn(`No options found for field ${fieldId} in context ${contextId}`)
        return []
      }

      return options.map((option) => ({
        id: option.id,
        name: option.value,
      }))
    } catch (error) {
      interactor.warn(`Error retrieving options for field ${fieldId}: ${error}`)
      return []
    }
  }

  /**
   * Get all custom fields from Jira
   * @param jiraBaseUrl Jira base URL
   * @param jiraApiToken Jira API token
   * @param jiraUsername Jira username
   * @param interactor Interactor for displaying messages
   * @returns Array of all custom fields
   */
  public static async getAllCustomFields(
    jiraBaseUrl: string,
    jiraApiToken: string,
    jiraUsername: string,
    interactor: Interactor
  ): Promise<JiraCustomField[]> {
    try {
      const allFields = await retrieveAllJiraFields(jiraBaseUrl, jiraApiToken, jiraUsername, interactor)
      const customFields = allFields.filter((field) => field.custom)
      return customFields
    } catch (error) {
      interactor.warn(`Error retrieving custom fields: ${error}`)
      return []
    }
  }

  /**
   * Format a custom field value for Jira API based on field type and metadata
   * @param fieldMeta The field metadata from Jira
   * @param value The value to format
   * @param allowedOptions Optional array of allowed options for the field
   * @returns Properly formatted value for Jira API
   */
  public static formatCustomFieldValue(
    fieldMeta: any,
    value: any,
    allowedOptions?: Array<{ id: string; name: string }>
  ): any {
    // If no value, return null
    if (value === null || value === undefined) {
      return null
    }

    // If value is already an object with id or name, use it
    if (typeof value === 'object' && value !== null) {
      if (value.id) {
        return { id: value.id }
      }
      if (value.name) {
        return { name: value.name }
      }
      // If array, leave as is (for multi-select fields)
      if (Array.isArray(value)) {
        return value
      }
    }

    // Check if field has allowedValues in its metadata
    const hasAllowedValues =
      fieldMeta?.allowedValues && Array.isArray(fieldMeta.allowedValues) && fieldMeta.allowedValues.length > 0

    // If this is a select field (has allowedValues or we were provided options)
    if (hasAllowedValues || (allowedOptions && allowedOptions.length > 0)) {
      const options = hasAllowedValues ? fieldMeta.allowedValues : allowedOptions

      // If value is a string, try to match to an option
      if (typeof value === 'string') {
        // Try to find a match by name (case insensitive)
        const matchByName = options.find(
          (opt: any) => (opt.name || opt.value || '').toLowerCase() === value.toLowerCase()
        )

        if (matchByName) {
          return { id: matchByName.id }
        }

        // If no match by name and value looks like an ID, use it directly
        if (/^\d+$/.test(value)) {
          return { id: value }
        }

        // If all else fails, use as name
        return { name: value }
      }
    }

    // If field is a user field (schema type is 'user')
    if (fieldMeta?.schema?.type === 'user') {
      if (typeof value === 'string') {
        // For user fields, we typically need accountId, but can try with name
        return { name: value }
      }
    }

    // Default: return value as-is
    return value
  }

  /**
   * Helper method to check if a field is a custom field
   * @param fieldKey The key of the field
   * @returns True if field is a custom field
   */
  public static isCustomField(fieldKey: string): boolean {
    return fieldKey.startsWith('customfield_')
  }

  /**
   * Determine if a field requires complex object formatting based on metadata
   * @param fieldMeta Field metadata from Jira
   * @returns True if field requires object formatting
   */
  public static requiresObjectFormatting(fieldMeta: any): boolean {
    if (!fieldMeta || !fieldMeta.schema) return false

    // Fields that typically require object formatting
    const objectTypes = ['option', 'array', 'user', 'group', 'version', 'component', 'project']

    // Check schema type
    if (objectTypes.includes(fieldMeta.schema.type)) return true

    // Check for allowedValues
    if (fieldMeta.allowedValues && Array.isArray(fieldMeta.allowedValues) && fieldMeta.allowedValues.length > 0) {
      return true
    }

    return false
  }
}
