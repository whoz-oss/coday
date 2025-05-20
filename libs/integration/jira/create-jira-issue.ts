import { Interactor } from '../../model'
import { CreateJiraIssueRequest } from './jira'
import axios from 'axios'
import { JiraCustomFieldHelper } from './jira-custom-field-helper'

/**
 * Log levels for controlling verbosity of interactor.displayText()
 */
export enum LogLevel {
  SILENT = 0,
  ERROR = 1,
  WARN = 2,
  INFO = 3,
  DEBUG = 4,
}

/**
 * Interface for issue type metadata from Jira API (fields is array)
 */
interface IssueTypeMetadata {
  id: string
  name: string
  subtask: boolean
  fields: FieldMetadata[]
}

/**
 * Interface for field metadata from Jira API
 */
interface FieldMetadata {
  required: boolean
  schema: {
    type: string
    items?: string
    custom?: string
    customId?: number
    system?: string
  }
  name: string
  key?: string
  hasDefaultValue?: boolean
  operations?: string[]
  allowedValues?: Array<{
    id?: string
    value?: string
    name?: string
    key?: string
    [key: string]: any
  }>
  defaultValue?: any
}

/**
 * Comprehensive error context for recovery, retry, and diagnostics
 */
export interface CreateJiraIssueErrorContext {
  error: string
  suggestion?: string
  failedField?: string
  partialRequest: Partial<CreateJiraIssueRequest>
  metadata?: IssueTypeMetadata
  projectKey?: string
  issueTypeName?: string
  issueTypeId?: string
  httpStatus?: number
  httpBody?: any
}

// Logging helper
function log(message: string, level: LogLevel, currentLevel: LogLevel, interactor: Interactor) {
  if (level <= currentLevel && currentLevel !== LogLevel.SILENT) {
    let prefix = ''
    if (level === LogLevel.ERROR) prefix = '[error] '
    else if (level === LogLevel.WARN) prefix = '[warning] '
    else if (level === LogLevel.INFO) prefix = '[info] '
    else if (level === LogLevel.DEBUG) prefix = '[debug] '
    interactor.displayText(`${prefix}${message}`)
  }
}

/**
 * Enhanced array field conversion utility with detailed validation and transformation logs
 */
export function convertToArrayField(
  value: any,
  fieldMeta: FieldMetadata,
  interactor: Interactor,
  logLevel: LogLevel = LogLevel.INFO
): any[] {
  const fieldLabel = `${fieldMeta.name} (${fieldMeta.key})`
  log(`Converting input for array field: ${fieldLabel}`, LogLevel.DEBUG, logLevel, interactor)

  if (value == null) {
    log(`No value provided for ${fieldLabel}.`, LogLevel.WARN, logLevel, interactor)
    return []
  }
  if (Array.isArray(value)) {
    log(`Input is already an array for ${fieldLabel}: ${JSON.stringify(value)}`, LogLevel.DEBUG, logLevel, interactor)
    return value
  }
  if (typeof value === 'string') {
    log(
      `Input is a string, attempting to split into array for ${fieldLabel}. Input: "${value}"`,
      LogLevel.DEBUG,
      logLevel,
      interactor
    )
    const arr = value
      .split(',')
      .map((v) => v.trim())
      .filter(Boolean)
    if (fieldMeta.key === 'labels') {
      log(`Treated as labels array: ${JSON.stringify(arr)}`, LogLevel.INFO, logLevel, interactor)
      return arr
    }
    if (fieldMeta.key === 'components' || fieldMeta.key === 'fixVersions') {
      const compArr = arr.map((name) => ({ name }))
      log(`Treated as array of {name}: ${JSON.stringify(compArr)}`, LogLevel.INFO, logLevel, interactor)
      return compArr
    }
    if (fieldMeta.schema.items === 'string') {
      log(`Treated as generic string array: ${JSON.stringify(arr)}`, LogLevel.INFO, logLevel, interactor)
      return arr
    }
    if (fieldMeta.schema.items === 'option') {
      const optArr = arr.map((item) => ({ value: item }))
      log(`Treated as array of {value}: ${JSON.stringify(optArr)}`, LogLevel.INFO, logLevel, interactor)
      return optArr
    }
    log(
      `String array conversion did not match a known schema for ${fieldLabel}. Result: ${JSON.stringify(arr)}`,
      LogLevel.WARN,
      logLevel,
      interactor
    )
    return arr
  }
  if (typeof value === 'object') {
    log(`Input is an object, wrapping in array for ${fieldLabel}.`, LogLevel.INFO, logLevel, interactor)
    return [value]
  }
  log(
    `Unexpected value type (${typeof value}) for ${fieldLabel}; wrapping as single-value array.`,
    LogLevel.WARN,
    logLevel,
    interactor
  )
  return [value]
}

/**
 * Retry wrapper for user field input with clear logging and guidance
 */
async function promptWithRetry<T>(
  promptFn: () => Promise<T>,
  validateFn: (result: T) => string | null, // returns error msg or null if ok
  interactor: Interactor,
  logLevel: LogLevel,
  fieldLabel: string,
  maxAttempts = 3
): Promise<T | undefined> {
  let attempts = 0
  while (attempts < maxAttempts) {
    attempts++
    const result = await promptFn()
    const errorMsg = validateFn(result)
    if (!errorMsg) {
      if (attempts > 1)
        log(`Successfully entered a valid value for ${fieldLabel}.`, LogLevel.INFO, logLevel, interactor)
      return result
    }
    log(`${errorMsg} (Attempt ${attempts}/${maxAttempts})`, LogLevel.WARN, logLevel, interactor)
    if (attempts === maxAttempts) {
      log(`Maximum attempts reached for ${fieldLabel}.`, LogLevel.ERROR, logLevel, interactor)
      return undefined
    }
    log(`Please try again for ${fieldLabel}.`, LogLevel.INFO, logLevel, interactor)
  }
  return undefined
}

function validateArrayFieldContent(
  arr: any[],
  fieldMeta: FieldMetadata,
  interactor: Interactor,
  logLevel: LogLevel
): boolean {
  if (fieldMeta.required && (!arr || arr.length === 0)) {
    log(`Required array field ${fieldMeta.name} cannot be empty.`, LogLevel.WARN, logLevel, interactor)
    return false
  }
  // Could add more validation here (type checks, allowedValues checks, etc.)
  return true
}

function logErrorContext(ctx: CreateJiraIssueErrorContext, interactor: Interactor, logLevel: LogLevel) {
  log(ctx.error, LogLevel.ERROR, logLevel, interactor)
  if (ctx.suggestion) log(ctx.suggestion, LogLevel.INFO, logLevel, interactor)
  if (ctx.failedField) log(`Issue with field: ${ctx.failedField}`, LogLevel.WARN, logLevel, interactor)
  if (ctx.httpStatus) log(`HTTP Status: ${ctx.httpStatus}`, LogLevel.ERROR, logLevel, interactor)
  if (ctx.httpBody) log(`HTTP Response: ${JSON.stringify(ctx.httpBody)}`, LogLevel.ERROR, logLevel, interactor)
}

/**
 * Format description field for Jira API
 */
function formatDescription(description: string): any {
  return {
    type: 'doc',
    version: 1,
    content: [
      {
        type: 'paragraph',
        content: [
          {
            type: 'text',
            text: description,
          },
        ],
      },
    ],
  }
}

/**
 * Function to retrieve field options for a custom field by ID
 */
async function getFieldOptions(
  fieldId: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor,
  logLevel: LogLevel = LogLevel.INFO
): Promise<Array<{ id: string; name: string }>> {
  try {
    log(`Retrieving valid options for field ${fieldId}...`, LogLevel.INFO, logLevel, interactor)
    const options = await JiraCustomFieldHelper.getFieldOptionsById(
      fieldId,
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      interactor
    )

    if (options.length > 0) {
      log(`Found ${options.length} valid options for field ${fieldId}`, LogLevel.INFO, logLevel, interactor)
      return options
    } else {
      log(
        `No options found for field ${fieldId}. This might cause issues when creating tickets.`,
        LogLevel.WARN,
        logLevel,
        interactor
      )
      return []
    }
  } catch (error: any) {
    log(`Error retrieving field options: ${error.message}`, LogLevel.ERROR, logLevel, interactor)
    return []
  }
}

/**
 * Function to retrieve the Jira user ID by username
 */
async function getJiraUserIdByUsername(
  username: string,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor,
  logLevel: LogLevel = LogLevel.INFO
): Promise<string | null> {
  try {
    log(`Retrieving Jira user ID for username: ${username}`, LogLevel.INFO, logLevel, interactor)
    const url = `${jiraBaseUrl}/rest/api/3/user/search?query=${encodeURIComponent(username)}`
    const response = await axios.get(url, {
      auth: {
        username: jiraUsername,
        password: jiraApiToken,
      },
    })

    if (response.data && Array.isArray(response.data) && response.data.length > 0) {
      const userId = response.data[0].accountId
      log(`Found user ID: ${userId} for username: ${username}`, LogLevel.DEBUG, logLevel, interactor)
      return userId
    } else {
      log(`No user found with username: ${username}`, LogLevel.WARN, logLevel, interactor)
      return null
    }
  } catch (error: any) {
    log(`Error retrieving user ID: ${error.message}`, LogLevel.ERROR, logLevel, interactor)
    return null
  }
}

/**
 * Main Jira issue creation function with robust logging, error context, array field handling, and retry logic
 */
export async function createJiraIssue(
  request: Partial<CreateJiraIssueRequest> = {} as CreateJiraIssueRequest,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor,
  logLevel: LogLevel = LogLevel.INFO
): Promise<{ issueKey: string; issueUrl: string } | CreateJiraIssueErrorContext> {
  // Ensure we preserve all provided request properties
  let finalRequest: Partial<CreateJiraIssueRequest> = { ...request }
  let context: CreateJiraIssueErrorContext | null = null
  let maxRetries = 3
  let retryCount = 0
  let projectKey: string | undefined = finalRequest.projectKey
  let issueTypeName: string | undefined = finalRequest.issuetype
  let issueTypeId: string | undefined = undefined
  let metadata: IssueTypeMetadata | undefined = undefined

  function getFieldMeta(key: string): FieldMetadata | undefined {
    return metadata?.fields.find((f) => f.key === key)
  }

  while (retryCount < maxRetries) {
    try {
      // --- Stage 1: Project and metadata validation ---
      if (!projectKey) {
        projectKey = await interactor.promptText('Enter the Jira project key (e.g., PROJ):')
        if (!projectKey) {
          context = {
            error: 'Project key is required.',
            suggestion: 'Enter a valid Jira project key (e.g., PROJ).',
            partialRequest: finalRequest,
          }
          logErrorContext(context, interactor, logLevel)
          return context
        }
      }

      finalRequest.projectKey = projectKey
      finalRequest.project = { key: projectKey }

      // Set reporter to the callee (current user) if not explicitly provided
      // First check if reporter is in the original request or already in finalRequest
      if (!finalRequest.reporter) {
        if (request.reporter) {
          // Use the reporter from the original request if provided
          finalRequest.reporter = request.reporter
          log(`Using provided reporter: ${JSON.stringify(request.reporter)}`, LogLevel.INFO, logLevel, interactor)
        } else {
          // Only set default reporter if not provided in request
          log(`Setting reporter to current user (${jiraUsername})`, LogLevel.INFO, logLevel, interactor)
          const userId = await getJiraUserIdByUsername(
            jiraUsername,
            jiraBaseUrl,
            jiraApiToken,
            jiraUsername,
            interactor,
            logLevel
          )
          if (userId) {
            finalRequest.reporter = { id: userId }
            log(`Using reporter ID: ${userId}`, LogLevel.DEBUG, logLevel, interactor)
          } else {
            log(
              `Could not retrieve user ID for ${jiraUsername}, falling back to username`,
              LogLevel.WARN,
              logLevel,
              interactor
            )
            finalRequest.reporter = { name: jiraUsername }
          }
        }
      }

      // Validate project exists
      try {
        const url = `${jiraBaseUrl}/rest/api/3/project/${projectKey}`
        await axios.get(url, {
          auth: {
            username: jiraUsername,
            password: jiraApiToken,
          },
        })
        log(`Project key ${projectKey} is valid.`, LogLevel.INFO, logLevel, interactor)
      } catch (error: any) {
        context = {
          error: `Project key ${projectKey} not found or not accessible.`,
          suggestion: 'Check your Jira project key and permissions.',
          partialRequest: finalRequest,
          httpStatus: error?.response?.status,
          httpBody: error?.response?.data,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        projectKey = undefined
        continue
      }

      // --- Stage 2: Issue type validation and metadata retrieval ---
      let issueTypes: Array<{ id: string; name: string }> = []
      try {
        const url = `${jiraBaseUrl}/rest/api/3/issue/createmeta/${projectKey}/issuetypes`
        const response = await axios.get(url, {
          auth: {
            username: jiraUsername,
            password: jiraApiToken,
          },
        })
        issueTypes = response.data.issueTypes.map((type: any) => ({ id: type.id, name: type.name }))
        log(`Loaded ${issueTypes.length} issue types for project ${projectKey}.`, LogLevel.DEBUG, logLevel, interactor)
      } catch (error: any) {
        context = {
          error: `Failed to retrieve issue types for project ${projectKey}.`,
          suggestion: 'Ensure the project key is correct and API credentials are valid.',
          partialRequest: finalRequest,
          httpStatus: error?.response?.status,
          httpBody: error?.response?.data,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        continue
      }

      if (!issueTypeName) {
        const names = issueTypes.map((t) => t.name)
        issueTypeName = await interactor.chooseOption(
          names,
          'Select an issue type:',
          'Please choose one of the available issue types.'
        )
        finalRequest.issuetype = issueTypeName
      } else {
        // If issueTypeName was provided in the request, make sure it's in finalRequest
        finalRequest.issuetype = issueTypeName
      }

      // Find issue type with exact match
      let issueTypeObj = issueTypes.find((t) => t.name === issueTypeName)

      // If no exact match, try fuzzy matching
      if (!issueTypeObj && issueTypeName) {
        log(`Issue type "${issueTypeName}" not found, trying fuzzy matching...`, LogLevel.INFO, logLevel, interactor)

        // Normalize the search term
        const normalizedSearch = issueTypeName.toLowerCase().trim()

        // Find closest match
        let bestMatch: { id: string; name: string; score: number } | null = null

        for (const type of issueTypes) {
          const typeName = type.name.toLowerCase()

          // Exact substring match (e.g., "Story" in "User Story")
          if (typeName.includes(normalizedSearch) || normalizedSearch.includes(typeName)) {
            const score = Math.abs(typeName.length - normalizedSearch.length)
            if (!bestMatch || score < bestMatch.score) {
              bestMatch = { ...type, score }
            }
          }
        }

        if (bestMatch) {
          issueTypeObj = { id: bestMatch.id, name: bestMatch.name }
          log(`Found fuzzy match: "${issueTypeName}" -> "${bestMatch.name}"`, LogLevel.INFO, logLevel, interactor)
          finalRequest.issuetype = bestMatch.name
          issueTypeName = bestMatch.name
        }
      }

      if (!issueTypeObj) {
        context = {
          error: `Issue type "${issueTypeName}" not found in project ${projectKey}.`,
          suggestion: `Choose a valid issue type. Available: ${issueTypes.map((t) => t.name).join(', ')}`,
          partialRequest: finalRequest,
          projectKey,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        issueTypeName = undefined
        continue
      }
      issueTypeId = issueTypeObj.id

      // Get metadata for selected issue type
      try {
        const url = `${jiraBaseUrl}/rest/api/3/issue/createmeta/${projectKey}/issuetypes/${issueTypeId}`
        const response = await axios.get(url, {
          auth: {
            username: jiraUsername,
            password: jiraApiToken,
          },
        })
        metadata = response.data as IssueTypeMetadata
        log(`Loaded metadata for issue type "${issueTypeName}".`, LogLevel.DEBUG, logLevel, interactor)
      } catch (error: any) {
        context = {
          error: `Failed to retrieve metadata for issue type ${issueTypeName}.`,
          suggestion: 'Check Jira permissions and API credentials.',
          partialRequest: finalRequest,
          projectKey,
          issueTypeName,
          issueTypeId,
          httpStatus: error?.response?.status,
          httpBody: error?.response?.data,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        continue
      }

      // --- Stage 3: Required field pre-check and progressive collection ---
      // Collect all required fields missing from finalRequest
      // Make sure we properly consider all fields from the original request as well
      const requiredFields = metadata.fields.filter(
        (f) =>
          f.required &&
          !finalRequest[f.key as keyof CreateJiraIssueRequest] &&
          !request[f.key as keyof CreateJiraIssueRequest]
      )

      // First copy any fields from the original request that aren't already in finalRequest
      for (const field of metadata.fields) {
        const key = field.key as keyof CreateJiraIssueRequest
        if (request[key] !== undefined && finalRequest[key] === undefined) {
          finalRequest[key] = request[key]
          log(`Using provided value for ${field.name} (${field.key})`, LogLevel.INFO, logLevel, interactor)
        }
        // Use default value if available and value not provided
        else if (
          field.hasDefaultValue &&
          field.defaultValue !== undefined &&
          finalRequest[key] === undefined &&
          request[key] === undefined
        ) {
          finalRequest[key] = field.defaultValue
          log(
            `Using default value for ${field.name} (${field.key}): ${JSON.stringify(field.defaultValue)}`,
            LogLevel.INFO,
            logLevel,
            interactor
          )
        }
      }

      for (const field of requiredFields) {
        let value: any
        if (field.schema.type === 'array') {
          // Show default value in prompt if available
          const defaultPrompt =
            field.hasDefaultValue && field.defaultValue
              ? `Enter values for ${field.name} (${field.key}), separated by commas:`
              : `Enter values for ${field.name} (${field.key}), separated by commas:`

          value = await promptWithRetry(
            async () => {
              const input = await interactor.promptText(
                `Enter values for ${field.name} (${field.key}), separated by commas:`,
                field.hasDefaultValue && field.defaultValue ? field.defaultValue : null
              )
              // Use default if input is empty and default exists
              return input === '' && field.hasDefaultValue && field.defaultValue ? field.defaultValue : input
            },
            (input) => {
              const arr = convertToArrayField(input, field, interactor, logLevel)
              if (!validateArrayFieldContent(arr, field, interactor, logLevel)) {
                return `At least one value required.`
              }
              return null
            },
            interactor,
            logLevel,
            field.name || field.key || 'field',
            3
          )
          if (value !== undefined) value = convertToArrayField(value, field, interactor, logLevel)
        } else if (Array.isArray(field.allowedValues) && field.allowedValues.length > 0) {
          // Select from allowed values
          const names = [...field.allowedValues.map((v) => v.name || v.value || v.key || v.id)] as string[]

          // If there's a default value, find its name to highlight in the prompt
          let defaultOption: string | undefined = undefined
          if (field.hasDefaultValue && field.defaultValue) {
            // Find the matching allowed value based on field.defaultValue
            const defaultId = typeof field.defaultValue === 'object' ? field.defaultValue.id : field.defaultValue
            const defaultMatch = field.allowedValues.find(
              (v) => v.id === defaultId || v.value === defaultId || v.key === defaultId
            )
            if (defaultMatch) {
              defaultOption = defaultMatch.name || defaultMatch.value || defaultMatch.key || defaultMatch.id
              log(`Found default option for ${field.name}: ${defaultOption}`, LogLevel.DEBUG, logLevel, interactor)
            }
          }

          value = await promptWithRetry(
            async () => {
              // If there's a default, use it as the preselected option
              if (defaultOption && names.includes(defaultOption)) {
                return interactor.chooseOption(
                  names,
                  `Select value for ${field.name} (${field.key}) [default: ${defaultOption}]:`,
                  'Choose an option'
                )
              } else {
                return interactor.chooseOption(
                  names,
                  `Select value for ${field.name} (${field.key}):`,
                  'Choose an option'
                )
              }
            },
            (input) => {
              if (!names.includes(input)) return 'Invalid selection.'
              return null
            },
            interactor,
            logLevel,
            field.name || field.key || 'field',
            3
          )

          // Convert the selected name back to the proper object format expected by Jira
          if (value !== undefined) {
            const selectedOption = field.allowedValues.find(
              (v) => v.name === value || v.value === value || v.key === value || v.id === value
            )

            if (selectedOption) {
              // Use the same format as in the allowedValues
              if (selectedOption.id) {
                value = { id: selectedOption.id }
              } else if (selectedOption.key) {
                value = { key: selectedOption.key }
              } else if (selectedOption.value) {
                value = { value: selectedOption.value }
              }
              log(`Formatted ${field.name} value to: ${JSON.stringify(value)}`, LogLevel.DEBUG, logLevel, interactor)
            }
          }
        } else {
          // Show default value in prompt if available
          const defaultPrompt =
            field.hasDefaultValue && field.defaultValue
              ? `Enter ${field.name} (${field.key}) [default: ${JSON.stringify(field.defaultValue)}]:`
              : `Enter ${field.name} (${field.key}):`

          value = await promptWithRetry(
            async () => {
              const input = await interactor.promptText(defaultPrompt)
              // Use default if input is empty and default exists
              return input === '' && field.hasDefaultValue && field.defaultValue ? field.defaultValue : input
            },
            (input) => {
              if (field.required && (!input || (typeof input === 'string' && input.trim() === ''))) {
                return 'Field is required.'
              }
              return null
            },
            interactor,
            logLevel,
            field.name || field.key || 'field',
            3
          )
        }
        finalRequest[field.key as keyof CreateJiraIssueRequest] = value
      }

      // --- Stage 4: Final comprehensive validation ---
      let missingFieldMeta = metadata.fields.find(
        (f) =>
          f.required &&
          (!finalRequest[f.key as keyof CreateJiraIssueRequest] ||
            (Array.isArray(finalRequest[f.key as keyof CreateJiraIssueRequest]) &&
              (finalRequest[f.key as keyof CreateJiraIssueRequest] as any[]).length === 0))
      )
      if (missingFieldMeta) {
        context = {
          error: `Missing required field: ${missingFieldMeta.name} (${missingFieldMeta.key})`,
          suggestion: 'Please provide a value.',
          failedField: missingFieldMeta.key,
          partialRequest: finalRequest,
          metadata,
          projectKey,
          issueTypeName,
          issueTypeId,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        continue
      }

      // --- Stage 5: Submit to Jira API ---
      const url = `${jiraBaseUrl}/rest/api/3/issue`
      const auth = Buffer.from(`${jiraUsername}:${jiraApiToken}`).toString('base64')
      const fields: Record<string, any> = {}
      // Add fields
      fields.issuetype = { id: issueTypeId }
      if (finalRequest.summary) fields.summary = finalRequest.summary
      if (finalRequest.description) fields.description = formatDescription(finalRequest.description)

      for (const [key, value] of Object.entries(finalRequest)) {
        if (['projectKey', 'issuetype', 'summary', 'description'].includes(key)) continue
        const meta = getFieldMeta(key)
        if (!meta) continue

        // Special handling for custom fields that require specific formats
        if (key.startsWith('customfield_')) {
          // Check if this is a field with allowedValues (like Squad/Team fields)
          if (meta.allowedValues && Array.isArray(meta.allowedValues) && meta.allowedValues.length > 0) {
            // If the value is a string, try to find matching allowed value
            if (typeof value === 'string') {
              // Try to find by name first
              const matchByName = meta.allowedValues.find(
                (av) =>
                  (av.name || '').toLowerCase() === value.toLowerCase() ||
                  (av.value || '').toLowerCase() === value.toLowerCase()
              )

              if (matchByName) {
                // Use the proper object format with id
                fields[key] = { id: matchByName.id }
                log(
                  `Mapped ${key} string value "${value}" to ID: ${matchByName.id}`,
                  LogLevel.INFO,
                  logLevel,
                  interactor
                )
                continue
              } else {
                // If no match by name, check if the value itself is an ID
                const matchById = meta.allowedValues.find((av) => av.id === value)
                if (matchById) {
                  fields[key] = { id: value }
                  log(`Used ${key} value "${value}" directly as ID`, LogLevel.INFO, logLevel, interactor)
                  continue
                }

                // If we got here, we couldn't match the string value
                log(
                  `Warning: Could not match ${key} value "${value}" to any allowed values. Will try as-is.`,
                  LogLevel.WARN,
                  logLevel,
                  interactor
                )
                fields[key] = { name: value } // Try with name as fallback
                continue
              }
            }
            // If value is already an object with id or name
            else if (typeof value === 'object' && value !== null) {
              if (value.id) {
                fields[key] = { id: value.id }
                log(`Used object with ID for ${key}: ${value.id}`, LogLevel.INFO, logLevel, interactor)
                continue
              } else if (value.name) {
                fields[key] = { name: value.name }
                log(`Used object with name for ${key}: ${value.name}`, LogLevel.INFO, logLevel, interactor)
                continue
              }
            }
          }
        }

        // Standard handling for other fields
        if (meta.schema.type === 'array') {
          fields[key] = convertToArrayField(value, meta, interactor, logLevel)
        } else if (Array.isArray(meta.allowedValues) && meta.allowedValues.length > 0) {
          // If value is a string but field expects an object with ID
          if (typeof value === 'string') {
            // Try to find the matching allowed value
            const matchingValue = meta.allowedValues.find(
              (av) => av.name === value || av.value === value || av.key === value || av.id === value
            )

            if (matchingValue) {
              // Use the proper object format with id
              if (matchingValue.id) {
                fields[key] = { id: matchingValue.id }
              } else if (matchingValue.key) {
                fields[key] = { key: matchingValue.key }
              } else if (matchingValue.value) {
                fields[key] = { value: matchingValue.value }
              } else {
                fields[key] = value // Fallback
              }
              log(
                `Mapped ${key} string value "${value}" to: ${JSON.stringify(fields[key])}`,
                LogLevel.INFO,
                logLevel,
                interactor
              )
            } else {
              fields[key] = value // Fallback to using the string value directly
            }
          } else {
            fields[key] = value
          }
        } else {
          fields[key] = value
        }
      }
      try {
        log(`Creating Jira issue...`, LogLevel.INFO, logLevel, interactor)
        const response = await fetch(url, {
          method: 'POST',
          headers: {
            Authorization: `Basic ${auth}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ fields }),
        })
        if (!response.ok) {
          const errorText = await response.text()
          context = {
            error: `Failed to create Jira issue (${response.status}): ${errorText}`,
            suggestion: 'Check required fields, permissions, and API configuration.',
            partialRequest: finalRequest,
            metadata,
            projectKey,
            issueTypeName,
            issueTypeId,
            httpStatus: response.status,
            httpBody: errorText,
          }
          logErrorContext(context, interactor, logLevel)
          retryCount++
          continue
        }
        const result = await response.json()
        const issueKey = result.key
        const issueUrl = `${jiraBaseUrl}/browse/${issueKey}`
        log(`Successfully created Jira issue ${issueKey}`, LogLevel.INFO, logLevel, interactor)
        log(`Issue can be accessed at: ${issueUrl}`, LogLevel.INFO, logLevel, interactor)
        return { issueKey, issueUrl }
      } catch (error: any) {
        context = {
          error: `Error creating Jira issue: ${error.message}`,
          suggestion: 'Check your network connection and Jira API status.',
          partialRequest: finalRequest,
          metadata,
          projectKey,
          issueTypeName,
          issueTypeId,
        }
        logErrorContext(context, interactor, logLevel)
        retryCount++
        continue
      }
    } catch (err: any) {
      context = {
        error: `Fatal error: ${err.message}`,
        partialRequest: finalRequest,
      }
      logErrorContext(context, interactor, logLevel)
      return context
    }
  }
  if (context) return context
  return {
    error: 'Failed to create Jira issue after multiple attempts.',
    partialRequest: finalRequest,
  }
}
