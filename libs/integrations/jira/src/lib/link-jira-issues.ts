import { Interactor } from '@coday/model/interactor'

/**
 * Interface for the Jira issue link request
 */
export interface LinkJiraIssuesRequest {
  inwardIssueKey: string
  outwardIssueKey: string
  linkType: string
  comment?: string
  isEpicLink?: boolean
  // When linkType is 'parent', 'child', 'parent-child', etc., the parent-child relationship will be used
  // By default, inwardIssueKey is treated as the child and outwardIssueKey as the parent,
  // except for 'has child' and 'child-parent' which reverse this relationship
}

/**
 * Links two Jira issues with a specified relationship type
 * @param inwardIssueKey The issue key that is the source of the link (inward issue)
 * @param outwardIssueKey The issue key that is the target of the link (outward issue)
 * @param linkType The type of link to create between issues (e.g., 'relates to', 'blocks', 'is blocked by')
 *                For parent-child relationships, use 'parent', 'child', 'parent-child', 'child-parent', 'has parent', or 'has child'
 *                By default, inwardIssueKey is treated as the child and outwardIssueKey as the parent,
 *                except for 'has child' and 'child-parent' which reverse this relationship
 * @param comment Optional comment to add when creating the link
 * @param jiraBaseUrl The base URL for the Jira instance
 * @param jiraApiToken The API token for Jira authentication
 * @param jiraUsername The username for Jira authentication
 * @param interactor The interactor for displaying messages
 * @returns Object containing success status and message
 */
export async function linkJiraIssues(
  requestOrInwardIssueKey: LinkJiraIssuesRequest | string,
  outwardIssueKeyOrJiraBaseUrl: string,
  linkTypeOrJiraApiToken: string,
  commentOrJiraUsername: string | undefined,
  jiraBaseUrlOrInteractor: string | Interactor,
  jiraApiToken?: string,
  jiraUsername?: string,
  interactor?: Interactor
): Promise<{ success: boolean; message: string }> {
  let inwardIssueKey: string
  let outwardIssueKey: string
  let linkType: string
  let comment: string | undefined
  let jiraBaseUrl: string
  let actualJiraApiToken: string
  let actualJiraUsername: string
  let actualInteractor: Interactor

  // Handle different parameter patterns
  if (typeof requestOrInwardIssueKey === 'object') {
    // Using request object pattern
    const request = requestOrInwardIssueKey
    inwardIssueKey = request.inwardIssueKey
    outwardIssueKey = request.outwardIssueKey
    linkType = request.linkType
    comment = request.comment
    jiraBaseUrl = outwardIssueKeyOrJiraBaseUrl
    actualJiraApiToken = linkTypeOrJiraApiToken
    actualJiraUsername = commentOrJiraUsername as string
    actualInteractor = jiraBaseUrlOrInteractor as Interactor
  } else {
    // Using individual parameters pattern
    inwardIssueKey = requestOrInwardIssueKey
    outwardIssueKey = outwardIssueKeyOrJiraBaseUrl
    linkType = linkTypeOrJiraApiToken
    comment = commentOrJiraUsername
    jiraBaseUrl = jiraBaseUrlOrInteractor as string
    actualJiraApiToken = jiraApiToken!
    actualJiraUsername = jiraUsername!
    actualInteractor = interactor!
  }

  try {
    // Validate required parameters
    if (!inwardIssueKey || !outwardIssueKey || !linkType) {
      return {
        success: false,
        message: 'Missing required parameters: inwardIssueKey, outwardIssueKey, and linkType are required',
      }
    }

    // Create Authorization header using Base64 encoding
    const auth = Buffer.from(`${actualJiraUsername}:${actualJiraApiToken}`).toString('base64')

    // Check if this is an epic link or parent-child relationship
    const isEpicLink =
      (typeof requestOrInwardIssueKey === 'object' && requestOrInwardIssueKey.isEpicLink === true) ||
      linkType.toLowerCase() === 'epic-issue' ||
      linkType.toLowerCase() === 'epic' ||
      linkType.toLowerCase() === 'is epic of' ||
      linkType.toLowerCase() === 'is part of epic'

    const isParentChildRelationship =
      linkType.toLowerCase() === 'parent' ||
      linkType.toLowerCase() === 'child' ||
      linkType.toLowerCase() === 'parent-child' ||
      linkType.toLowerCase() === 'child-parent' ||
      linkType.toLowerCase() === 'has parent' ||
      linkType.toLowerCase() === 'has child' ||
      linkType.toLowerCase() === 'is parent of' ||
      linkType.toLowerCase() === 'is child of' ||
      linkType.toLowerCase() === 'subtask' ||
      linkType.toLowerCase() === 'is subtask of'

    // Log the type of linking we're performing
    let linkingMethod = 'standard issue linking'
    if (isEpicLink || isParentChildRelationship) {
      linkingMethod = 'Parent-Child relationship (Jira V3 API)'
    }
    actualInteractor.displayText(`Linking issues ${inwardIssueKey} and ${outwardIssueKey} using ${linkingMethod}.`)

    // Handle parent-child relationship (including epics) using Jira V3 API
    if (isEpicLink || isParentChildRelationship) {
      try {
        // Determine which issue is the parent and which is the child
        // By convention, we assume inwardIssueKey is the child and outwardIssueKey is the parent
        // unless the linkType specifically indicates otherwise
        let childKey = inwardIssueKey
        let parentKey = outwardIssueKey

        // If the relationship is specified as 'child', 'has parent', etc., keep the default
        // If specified as 'parent', 'has child', etc., reverse the roles
        if (
          linkType.toLowerCase() === 'parent' ||
          linkType.toLowerCase() === 'has child' ||
          linkType.toLowerCase() === 'child-parent' ||
          linkType.toLowerCase() === 'is parent of'
        ) {
          childKey = outwardIssueKey
          parentKey = inwardIssueKey
        }

        // For epic links, the epic is always the parent
        if (isEpicLink) {
          // Check if inwardIssueKey is an epic
          const epicCheckResponse = await fetch(`${jiraBaseUrl}/rest/api/3/issue/${inwardIssueKey}?fields=issuetype`, {
            method: 'GET',
            headers: {
              Authorization: `Basic ${auth}`,
              Accept: 'application/json',
            },
          })

          if (epicCheckResponse.ok) {
            const epicData = await epicCheckResponse.json()
            const isInwardEpic = epicData.fields?.issuetype?.name?.toLowerCase() === 'epic'

            if (isInwardEpic) {
              // If inwardIssueKey is an epic, it's the parent
              childKey = outwardIssueKey
              parentKey = inwardIssueKey
            } else {
              // Check if outwardIssueKey is an epic
              const outwardEpicCheckResponse = await fetch(
                `${jiraBaseUrl}/rest/api/3/issue/${outwardIssueKey}?fields=issuetype`,
                {
                  method: 'GET',
                  headers: {
                    Authorization: `Basic ${auth}`,
                    Accept: 'application/json',
                  },
                }
              )

              if (outwardEpicCheckResponse.ok) {
                const outwardEpicData = await outwardEpicCheckResponse.json()
                const isOutwardEpic = outwardEpicData.fields?.issuetype?.name?.toLowerCase() === 'epic'

                if (isOutwardEpic) {
                  // If outwardIssueKey is an epic, it's the parent
                  childKey = inwardIssueKey
                  parentKey = outwardIssueKey
                }
              }
            }
          }
        }

        actualInteractor.displayText(`Setting parent-child relationship: ${childKey} will be a child of ${parentKey}`)

        // First, verify both issues exist
        const [childExists, parentExists] = await Promise.all([
          fetch(`${jiraBaseUrl}/rest/api/3/issue/${childKey}?fields=issuetype`, {
            method: 'GET',
            headers: {
              Authorization: `Basic ${auth}`,
              Accept: 'application/json',
            },
          }),
          fetch(`${jiraBaseUrl}/rest/api/3/issue/${parentKey}?fields=issuetype`, {
            method: 'GET',
            headers: {
              Authorization: `Basic ${auth}`,
              Accept: 'application/json',
            },
          }),
        ])

        if (!childExists.ok) {
          return {
            success: false,
            message: `Child issue ${childKey} not found or not accessible. Status: ${childExists.status}`,
          }
        }

        if (!parentExists.ok) {
          return {
            success: false,
            message: `Parent issue ${parentKey} not found or not accessible. Status: ${parentExists.status}`,
          }
        }

        // Get issue types to check if they support parent-child relationship
        const [childData, parentData] = await Promise.all([childExists.json(), parentExists.json()])

        const childType = childData.fields?.issuetype?.name?.toLowerCase()
        const parentType = parentData.fields?.issuetype?.name?.toLowerCase()

        actualInteractor.displayText(`Child issue type: ${childType}, Parent issue type: ${parentType}`)

        // Use the Jira V3 API to set the parent-child relationship using the standardized 'parent' field
        const parentChildResponse = await fetch(`${jiraBaseUrl}/rest/api/3/issue/${childKey}`, {
          method: 'PUT',
          headers: {
            Authorization: `Basic ${auth}`,
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify({
            fields: {
              parent: {
                key: parentKey,
              },
            },
          }),
        })

        if (parentChildResponse.status === 204 || parentChildResponse.status === 200) {
          // If comment is provided, add it separately
          if (comment) {
            await addComment(childKey, comment, jiraBaseUrl, auth, actualInteractor)
          }

          return {
            success: true,
            message: `Successfully set parent-child relationship: ${childKey} is now a child of ${parentKey}`,
          }
        } else {
          // Try to get detailed error information
          let errorMessage = 'Unknown error'
          try {
            const errorData = await parentChildResponse.json()
            if (errorData.errors && Object.keys(errorData.errors).length > 0) {
              // Extract specific field errors
              const fieldErrors = Object.entries(errorData.errors)
                .map(([field, error]) => `${field}: ${error}`)
                .join(', ')
              errorMessage = `Field errors: ${fieldErrors}`
            } else if (errorData.errorMessages && errorData.errorMessages.length > 0) {
              errorMessage = errorData.errorMessages.join(', ')
            } else {
              errorMessage = JSON.stringify(errorData)
            }
          } catch (parseError) {
            errorMessage = `Status ${parentChildResponse.status}, couldn't parse error response`
          }

          // Provide detailed error information
          actualInteractor.displayText(`Parent-child update failed: ${errorMessage}`)
          actualInteractor.displayText(`Will try standard issue linking as fallback.`)
        }
      } catch (parentChildError) {
        actualInteractor.displayText(`Error during parent-child relationship setup: ${parentChildError}`)
        actualInteractor.displayText(`Will try standard issue linking as fallback.`)
      }
    }

    // If we get here, either it's not a parent-child relationship, or that method failed
    // Fall back to standard issue linking
    // Construct request payload
    const payload: any = {
      type: {
        name: linkType,
      },
      inwardIssue: {
        key: inwardIssueKey,
      },
      outwardIssue: {
        key: outwardIssueKey,
      },
    }

    // Add comment if provided
    if (comment) {
      payload.comment = {
        body: comment,
      }
    }

    // Send request to Jira API
    const response = await fetch(`${jiraBaseUrl}/rest/api/2/issueLink`, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(payload),
    })

    // Handle response
    if (response.status === 201) {
      return {
        success: true,
        message: `Successfully linked issues ${inwardIssueKey} and ${outwardIssueKey} with link type "${linkType}"`,
      }
    } else {
      const errorData = await response.json().catch(() => ({ errorMessages: ['Unknown error'] }))
      return {
        success: false,
        message: `Failed to link issues: ${JSON.stringify(errorData)}`,
      }
    }
  } catch (error) {
    actualInteractor.error(`Error in linkJiraIssues: ${error}`)
    return {
      success: false,
      message: `Error linking Jira issues: ${error instanceof Error ? error.message : String(error)}`,
    }
  }
}

/**
 * Helper function to add a comment to a Jira issue
 */
async function addComment(
  issueKey: string,
  comment: string,
  jiraBaseUrl: string,
  auth: string,
  interactor: Interactor
): Promise<boolean> {
  try {
    const response = await fetch(`${jiraBaseUrl}/rest/api/2/issue/${issueKey}/comment`, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        body: comment,
      }),
    })

    if (response.status === 201) {
      return true
    } else {
      interactor.displayText(`Failed to add comment to issue ${issueKey}. Status: ${response.status}`)
      return false
    }
  } catch (error) {
    interactor.error(`Error adding comment to issue ${issueKey}: ${error}`)
    return false
  }
}
