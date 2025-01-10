import axios from 'axios'
import {Interactor} from '../../model'
import {JiraField} from "./jira";
import {extractFieldOptionsFromTickets, generateMappingDescription} from "./jira.helpers";

export interface ActiveFieldOption {
    value: string;
    count: number;
}

export interface ActiveFieldMapping {
    id: string
    name: string
    key: string | undefined
    custom: boolean
    searchable: boolean
    options?: ActiveFieldOption[]
}

const DEPRECATED_FIELDS = {
    CLIENTS: 'customfield_10532',
    WORKLOAD: 'customfield_10626',
    PRICING: 'customfield_10628'
}

const DEPRECATED_FIELD_IDS = Object.values(DEPRECATED_FIELDS)

export class JiraFieldMapper {
    private baseUrl: string
    private apiToken: string
    private username: string
    private interactor: Interactor

    constructor(
        jiraBaseUrl: string,
        jiraApiToken: string,
        jiraUsername: string,
        interactor: Interactor
    ) {
        this.baseUrl = jiraBaseUrl
        this.apiToken = jiraApiToken
        this.username = jiraUsername
        this.interactor = interactor
    }

    async fetchTicketsFields(maxTickets: number = 500): Promise<any[]> {
        try {
            const searchResponse = await axios.post(
                `${this.baseUrl}/rest/api/3/search`,
                {
                    jql: 'ORDER BY created DESC', // Most recent tickets first
                    maxResults: maxTickets,
                    fields: ['*all'] // Fetch all fields to ensure we capture all fields
                },
                {
                    auth: {
                        username: this.username,
                        password: this.apiToken
                    }
                }
            )
            return searchResponse.data.issues
        } catch (error) {
            this.interactor.warn(`Could not fetch tickets: ${error}`)
            return []
        }
    }

    async createComprehensiveFieldMapping(maxTickets: number = 200): Promise<ActiveFieldMapping[]> {
        try {
            // Fetch all fields
            const fieldsResponse = await axios.get(`${this.baseUrl}/rest/api/3/field`, {
                auth: {
                    username: this.username,
                    password: this.apiToken
                }
            })

            // Filter for custom fields, excluding deprecated
            const activeJiraFields: JiraField[] = fieldsResponse.data.filter(
                (field: JiraField) =>
                    !DEPRECATED_FIELD_IDS.includes(field.id)
            )

            // Fetch tickets once
            const tickets = await this.fetchTicketsFields(maxTickets)

            // Extract options from tickets
            return extractFieldOptionsFromTickets(tickets, activeJiraFields)
        } catch (error) {
            this.interactor.warn(`Error creating field mapping: ${error}`)
            return []
        }
    }
}

// Utility function to generate field mapping
export async function generateFieldMapping(
    jiraBaseUrl: string,
    jiraApiToken: string,
    jiraUsername: string,
    interactor: Interactor,
    maxTickets: number = 100
): Promise<{
    mappings: ActiveFieldMapping[],
    description: string
}> {
    const mapper = new JiraFieldMapper(
        jiraBaseUrl,
        jiraApiToken,
        jiraUsername,
        interactor
    )

    const mappings = await mapper.createComprehensiveFieldMapping(maxTickets)
    const description = generateMappingDescription(mappings)
    return {mappings, description}
}