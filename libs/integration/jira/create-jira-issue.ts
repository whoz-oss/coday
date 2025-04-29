import { Interactor } from '../../model'
import { CreateJiraIssueRequest } from './jira'
import { searchJiraSquads } from './search-jira-squads'

/**
 * Create a new Jira issue using the Jira REST API v3
 */
export async function createJiraIssue(
  request: CreateJiraIssueRequest,
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor
): Promise<{ issueKey: string }> {
  const url = `${jiraBaseUrl}/rest/api/3/issue`
  const auth = Buffer.from(`${jiraUsername}:${jiraApiToken}`).toString('base64')

  try {
    // Extract required fields from request
    const { projectKey, summary, squad, ...otherFields } = request

    // Handle squad selection logic
    let selectedSquadId: string | undefined
    let selectedSquadName: string | undefined = squad

    // If squadSearch is provided and no squadId, search for squads
    interactor.displayText(`Searching for squad using term: "${squad}"`)
    const { squadCustomFieldId, squads } = await searchJiraSquads(
      squad,
      jiraBaseUrl,
      jiraApiToken,
      jiraUsername,
      interactor
    )

    if (squads.length === 0) {
      throw new Error(`No squads found matching "${squad}". Please try a different search term.`)
    } else if (squads.length === 1) {
      // Auto-select if only one match
      selectedSquadId = squads[0].id
      selectedSquadName = squads[0].name
      interactor.displayText(`Auto-selected squad: ${selectedSquadName} (ID: ${selectedSquadId})`)
    } else {
      // Display all matches and prompt user to select
      interactor.displayText('Multiple squads found. Please choose one:')
      squads.forEach((s, index) => {
        interactor.displayText(`${index + 1}. ${s.name} (ID: ${s.id})`)
      })

      // For this implementation, we'll choose the first one, but in a real UI, you would prompt the user
      selectedSquadId = squads[0].id
      selectedSquadName = squads[0].name
      interactor.displayText(`Selected first matching squad: ${selectedSquadName} (ID: ${selectedSquadId})`)
    }

    // Build the payload fields object
    const fields: Record<string, any> = {
      project: {
        key: projectKey,
      },
      [squadCustomFieldId]: {
        value: selectedSquadName,
        id: selectedSquadId,
      },
      summary: summary,
      issuetype: {
        name: otherFields.issuetype || 'Task', // Default to Task type
      },
    }

    // Add optional fields if provided
    if (otherFields.description) {
      fields.description = {
        type: 'doc',
        version: 1,
        content: [
          {
            type: 'paragraph',
            content: [
              {
                type: 'text',
                text: otherFields.description,
              },
            ],
          },
        ],
      }
    }

    // Add assignee if provided
    if (otherFields.assignee) {
      fields.assignee = {
        id: otherFields.assignee,
      }
    }

    // Add reporter if provided
    if (otherFields.reporter) {
      fields.reporter = {
        id: otherFields.reporter,
      }
    }

    // Add priority if provided
    if (otherFields.priority) {
      fields.priority = {
        name: otherFields.priority,
      }
    }

    // Add labels if provided
    if (otherFields.labels && Array.isArray(otherFields.labels)) {
      fields.labels = otherFields.labels
    }

    // Add components if provided
    if (otherFields.components && Array.isArray(otherFields.components)) {
      fields.components = otherFields.components.map((name) => ({ name }))
    }

    // Add fixVersions if provided
    if (otherFields.fixVersions && Array.isArray(otherFields.fixVersions)) {
      fields.fixVersions = otherFields.fixVersions.map((name) => ({ name }))
    }

    // Add due date if provided
    if (otherFields.duedate) {
      fields.duedate = otherFields.duedate
    }

    // Add any custom fields
    for (const [key, value] of Object.entries(otherFields)) {
      if (key.startsWith('customfield_')) {
        fields[key] = value
      }
    }

    // Create the payload
    const payload = {
      fields: fields,
    }

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(`Failed to create Jira issue: ${error}`)
    }

    const result = await response.json()
    const issueKey = result.key

    interactor.displayText(`Successfully created Jira issue ${issueKey}`)
    return { issueKey }
  } catch (error) {
    interactor.error(`Error creating Jira issue: ${error}`)
    throw error
  }
}
