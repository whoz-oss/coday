import { Interactor } from '../../model'
import { retrieveAutocompleteData } from './retrieve-autocomplete-data'
import { searchJiraTickets } from './search-jira-tickets'
import { createFieldMapping } from './jira.helpers'
import { AutocompleteDataResponse } from './jira'

export interface ActiveFieldMapping {
  name: string
  jqlQueryKey: string | undefined
  ticketCreationKey: string | undefined
  custom: boolean
  searchable?: string | null
  cfid?: string | null
  autocompleteValue?: string[]
  operators?: string[]
}

export class JiraFieldMapper {
  private readonly baseUrl: string
  private readonly apiToken: string
  private readonly username: string
  private readonly interactor: Interactor

  constructor(jiraBaseUrl: string, jiraApiToken: string, jiraUsername: string, interactor: Interactor) {
    this.baseUrl = jiraBaseUrl
    this.apiToken = jiraApiToken
    this.username = jiraUsername
    this.interactor = interactor
  }

  // Main method to generate comprehensive field mapping
  async generateFieldMapping(maxResults: number = 100): Promise<{
    mappings: ActiveFieldMapping[]
    autocompleteData: AutocompleteDataResponse
    description: { creationDescription: string; jqlResearchDescription: string }
  }> {
    try {
      // Fetch all required data with diverse ticket types
      const [autocompleteData, tickets] = await Promise.all([
        retrieveAutocompleteData(this.baseUrl, this.apiToken, this.username, this.interactor),
        searchJiraTickets({
          request: {
            jql: "project = 'WZ' AND created >= -30d ORDER BY created DESC",
            maxResults,
            fields: ['*all'],
          },
          jiraBaseUrl: this.baseUrl,
          jiraApiToken: this.apiToken,
          jiraUsername: this.username,
          interactor: this.interactor,
        }),
      ])

      // Create field mapping
      const mappings = createFieldMapping(autocompleteData.visibleFieldNames, tickets)

      const description = this.generateMappingDescription(mappings)
      return {
        mappings,
        autocompleteData,
        description,
      }
    } catch (error) {
      this.interactor.warn(`Error creating field mapping: ${error}`)
      return {
        mappings: [],
        autocompleteData: { visibleFieldNames: [] },
        description: {
          creationDescription: 'Failed to generate the jira ticket creation description',
          jqlResearchDescription: 'Failed to generate the jira jql research description',
        },
      }
    }
  }

  private generateMappingDescription(mappings: ActiveFieldMapping[]): {
    creationDescription: string
    jqlResearchDescription: string
  } {
    const creationFields = mappings
      .map(
        (field) => `    ${field.name} - ${field.custom ? 'Custom' : 'Standard'}
    Name: ${field.name}    
    Key: ${field.ticketCreationKey}`
      )
      .join('\n\n')

    const jqlFields = mappings
      .map((field) => {
        const jqlIdentifier = field?.cfid ?? field?.jqlQueryKey
        const operators = field.operators?.length ? ` [${field.operators.join(', ')}]` : ''

        return `    ${field.name} - ${field.custom ? 'Custom' : 'Standard'}
    Name: ${field.name}    
    Query Key: ${jqlIdentifier}
    Operator: ${operators}`
      })
      .join('\n\n')

    return {
      creationDescription: `# Jira Ticket Creation Fields (${mappings.length} fields)

${creationFields}

Notes:
- Only use these keys when creating tickets via API
- Custom fields might require specific values`,

      jqlResearchDescription: `# Jira JQL Search Fields (${mappings.length} fields)

${jqlFields}

Notes:
- Only use these keys in JQL queries
- Include operators where specified
- Custom fields may have restricted values`,
    }
  }
}
// Utility function for easy access
export async function createJiraFieldMapping(
  jiraBaseUrl: string,
  jiraApiToken: string,
  jiraUsername: string,
  interactor: Interactor,
  maxTickets: number = 100
): Promise<{
  mappings: ActiveFieldMapping[]
  autocompleteData: AutocompleteDataResponse
  description: {
    creationDescription: string
    jqlResearchDescription: string
  }
}> {
  const mapper = new JiraFieldMapper(jiraBaseUrl, jiraApiToken, jiraUsername, interactor)

  return mapper.generateFieldMapping(maxTickets)
}
