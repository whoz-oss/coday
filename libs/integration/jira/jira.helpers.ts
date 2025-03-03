import { ActiveFieldMapping } from './jira-field-mapper'
import { JiraFields, JiraIssue, LightWeightIssues, VisibleField } from './jira'

export function createFieldMapping(visibleFields: VisibleField[], issues: JiraIssue[]): ActiveFieldMapping[] {
  // Create a map to track field usage
  const fieldUsageMap = extractUsedFieldsFromJiraIssues(issues)

  // Filter fields with at least 2 unique values
  return Array.from(fieldUsageMap.entries()).map(([fieldKey, values]) => {
    // Find corresponding autocomplete data
    const autocompleteInfo = findAutocompleteInfo(visibleFields, fieldKey)
    return {
      name: autocompleteInfo?.displayName || fieldKey,
      jqlQueryKey: autocompleteInfo?.cfid || autocompleteInfo?.value || fieldKey,
      ticketCreationKey: fieldKey,
      custom: !!autocompleteInfo?.cfid || fieldKey.startsWith('customfield_'),
      searchable: autocompleteInfo?.searchable,
      cfid: autocompleteInfo?.cfid,
      autocompleteValue: Array.from(values).slice(0, 10), // Limit to 10 sample values
      operators: autocompleteInfo?.operators,
    }
  })
}

// Helper method to find autocomplete info
function findAutocompleteInfo(visibleFields: VisibleField[], fieldKey: string): VisibleField | undefined {
  const parts = fieldKey.split('_')
  const customFieldId = parts.length > 1 ? parts[1] : undefined

  return visibleFields.find((ac: VisibleField) => {
    if (customFieldId) {
      return (
        ac.cfid?.includes(customFieldId) || ac.value.includes(customFieldId) || ac.displayName.includes(customFieldId)
      )
    }
    return ac.value === fieldKey || ac.value.slice(0, -1) === fieldKey || ac.value === fieldKey.slice(0, -1)
  })
}

// Validate field value
function isValidFieldValue(value: any): boolean {
  if (value === null || value === undefined) {
    return false
  }

  if (Array.isArray(value)) {
    return value.length > 0
  }

  return true
}

// Normalize field value for consistent comparison
function normalizeFieldValue(value: any): string {
  return value?.value ?? value?.name ?? value?.displayName ?? value
}

function processFieldValues(fieldKey: string, fieldValue: any, fieldMap: Map<string, Set<any>>): void {
  if (!isValidFieldValue(fieldValue)) return

  if (!fieldMap.has(fieldKey)) {
    fieldMap.set(fieldKey, new Set())
  }

  const values = Array.isArray(fieldValue) ? fieldValue : [fieldValue]
  values.forEach((val) => {
    if (isValidFieldValue(val)) {
      fieldMap.get(fieldKey)!.add(normalizeFieldValue(val))
    }
  })
}

export function extractUsedFieldsFromJiraIssues(issues: JiraIssue[]): Map<string, Set<any>> {
  return issues.reduce((fieldUsageMap, ticket) => {
    ;(Object.keys(ticket.fields) as Array<keyof typeof ticket.fields>).forEach((fieldKey) => {
      processFieldValues(fieldKey, ticket.fields[fieldKey], fieldUsageMap)
    })
    return fieldUsageMap
  }, new Map<string, Set<any>>())
}

// Field presets for common use cases
export const JIRA_FIELD_PRESETS = {
  minimal: ['key', 'summary'],
  basic: ['key', 'summary', 'status'],
  detailed: ['key', 'summary', 'status', 'description', 'priority'],
  dates: ['created', 'updated', 'duedate'],
  navigation: ['key', 'parent', 'subtasks', 'issuelinks'],
  tracking: ['assignee', 'reporter', 'created', 'updated', 'status'],
} as const;

/**
 * Resolves field selections including presets into a final list of fields
 * @param fields Array of field names or presets
 * @returns Array of resolved field names
 */
export function resolveJiraFields(fields: string[] = ['basic']): string[] {
  // Handle special cases
  if (fields.includes('*all') || fields.includes('*navigable')) {
    return fields;
  }

  // Process each field, expanding presets
  const resolvedFields = fields.flatMap(field => {
    const preset = JIRA_FIELD_PRESETS[field.toLowerCase() as keyof typeof JIRA_FIELD_PRESETS];
    return preset || field;
  });

  // Remove duplicates while preserving order
  return Array.from(new Set(resolvedFields));
}

export function getLightWeightIssues(
  issues: JiraIssue[],
  fieldsToIgnore: Array<keyof JiraFields> = []
): LightWeightIssues {
  return issues.reduce((acc, ticket) => {
    // Initialize nested object for this ticket
    acc[ticket.key] = {} as Record<keyof JiraFields, unknown>

    // Add filtered fields to nested object
    //@ts-ignore
    Object.entries(ticket.fields).forEach(([fieldKey, fieldValue]) => {
      if (!fieldsToIgnore.includes(fieldKey as keyof JiraFields) && isValidFieldValue(fieldValue)) {
        //@ts-ignore
        acc[ticket.key][fieldKey] = normalizeFieldValue(fieldValue)
      }
    })

    return acc
  }, {} as LightWeightIssues)
}
