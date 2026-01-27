import { IntegrationService } from '@coday/service'
import { Interactor } from '@coday/model'
import { ActiveFieldMapping, createJiraFieldMapping } from './jira-field-mapper'
import { FieldMappingDescription } from './jira'

/**
 * Service responsible for Jira field mapping initialization
 */
export class JiraService {
  private jiraFieldMappingDescription: FieldMappingDescription | null = null
  private jiraFieldMapping: ActiveFieldMapping[] | null = null
  private initialized: boolean = false

  constructor(
    private interactor: Interactor,
    private integrationService: IntegrationService
  ) {}

  /**
   * Initializes the Jira field mapping. This should be called before using
   * searchJiraIssues or countJiraIssues functions.
   */
  public async init(maxResults: number = 50): Promise<{ success: boolean; message: string }> {
    // If already initialized, return immediately
    if (this.initialized) {
      return { success: true, message: 'Jira field mapping already initialized' }
    }

    try {
      // Check if Jira integration is available
      if (!this.integrationService.hasIntegration('JIRA')) {
        return { success: false, message: 'Jira integration not available' }
      }

      // Get Jira credentials
      const jiraBaseUrl = this.integrationService.getApiUrl('JIRA')
      const jiraUsername = this.integrationService.getUsername('JIRA')
      const jiraApiToken = this.integrationService.getApiKey('JIRA')

      // Validate credentials
      if (!(jiraBaseUrl && jiraUsername && jiraApiToken)) {
        return { success: false, message: 'Jira credentials not properly configured' }
      }

      // Create Jira field mapping
      const { description, mappings } = await createJiraFieldMapping(
        jiraBaseUrl,
        jiraApiToken,
        jiraUsername,
        this.interactor,
        maxResults
      )

      // Store mapping data
      this.jiraFieldMappingDescription = description
      this.jiraFieldMapping = mappings
      this.initialized = true

      return { success: true, message: 'Jira field mapping initialized successfully' }
    } catch (error) {
      return {
        success: false,
        message: `Failed to initialize Jira field mapping: ${error}. Please try again or contact support.`,
      }
    }
  }

  /**
   * Ensures the service is initialized and returns initialization status with field mapping information
   * This is the key method for lazy loading the field mapping data
   * @returns Object containing initialization status and field mapping data if newly initialized
   */
  public async ensureInitialized(maxResults: number = 50): Promise<{
    isNewlyInitialized: boolean
    fieldMappingInfo?: FieldMappingDescription | null
    fieldMapping?: ActiveFieldMapping[] | null
    success: boolean
    message: string
  }> {
    if (!this.initialized) {
      const initResult = await this.init(maxResults)

      if (!initResult.success) {
        return {
          isNewlyInitialized: false,
          success: false,
          message: initResult.message,
        }
      }

      return {
        isNewlyInitialized: true,
        fieldMappingInfo: this.jiraFieldMappingDescription || null,
        fieldMapping: this.jiraFieldMapping || null,
        success: true,
        message:
          'Jira service initialized. Field mapping information is now available. Please use the field mapping information above to refine your JQL query and try again.',
      }
    }

    return {
      isNewlyInitialized: false,
      fieldMappingInfo: this.jiraFieldMappingDescription || null,
      fieldMapping: this.jiraFieldMapping || null,
      success: true,
      message: 'Jira service was already initialized.',
    }
  }
}
