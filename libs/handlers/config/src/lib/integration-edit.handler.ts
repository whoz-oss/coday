import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model'
import { IntegrationConfigService } from '@coday/service'
import { ConfigLevel } from '@coday/model'
import { ConcreteIntegrations } from '@coday/model'
import { IntegrationConfig } from '@coday/model'
import { IntegrationLocalConfig } from '@coday/model'

const MASKED_VALUE = '********'

export class IntegrationEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: IntegrationConfigService
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit an integration configuration. Use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProjectLevel = !!args.project
    const targetLevel = isProjectLevel ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Extract integration name from remaining arguments
    const integrationName = args.rest.trim().toUpperCase()

    // If no integration name provided, show available integrations to choose from
    let selectedIntegration: string | undefined
    if (!integrationName || !ConcreteIntegrations.includes(integrationName)) {
      const availableIntegrations = this.getAvailableIntegrations(targetLevel)

      if (availableIntegrations.length === 0) {
        const levelName = isProjectLevel ? 'project' : 'user'
        this.interactor.displayText(
          `No integrations found at ${levelName} level. Use 'config integration add' to create one.`
        )
        return context
      }

      selectedIntegration = await this.selectIntegrationToEdit(availableIntegrations)
      if (!selectedIntegration) {
        this.interactor.displayText('No integration selected.')
        return context
      }
    } else {
      selectedIntegration = integrationName
    }

    // Get current configuration for context
    const currentConfig = this.service.getUnmaskedIntegration(selectedIntegration, targetLevel)
    const mergedConfig = this.service.getMergedIntegrations()

    // Edit the configuration
    const editedConfig = await this.editIntegrationWithContext(
      selectedIntegration,
      currentConfig || {},
      mergedConfig,
      targetLevel
    )

    // Save the configuration
    await this.service.saveIntegration(selectedIntegration, editedConfig, targetLevel)

    // Display success message
    const levelName = isProjectLevel ? 'project' : 'user'
    const successMessage = `
# ✅ Updated Integration Configuration

**Integration:** ${selectedIntegration}
**Level:** ${levelName}
**Status:** Successfully updated
`
    this.interactor.displayText(successMessage)

    return context
  }

  /**
   * Get available integrations for editing
   */
  private getAvailableIntegrations(targetLevel: ConfigLevel): string[] {
    const available: string[] = []

    // Current level integrations (direct edit)
    const currentLevelIntegrations = this.service.getIntegrations(targetLevel)
    available.push(...Object.keys(currentLevelIntegrations))

    // Lower level integrations (cloning allowed - only from PROJECT to USER)
    if (targetLevel === ConfigLevel.USER) {
      const projectIntegrations = this.service.getIntegrations(ConfigLevel.PROJECT)
      Object.keys(projectIntegrations).forEach((name) => {
        if (!available.includes(name)) {
          available.push(name)
        }
      })
    }

    return available.sort()
  }

  /**
   * Let user select which integration to edit
   */
  private async selectIntegrationToEdit(availableIntegrations: string[]): Promise<string | undefined> {
    if (availableIntegrations.length === 1) {
      return availableIntegrations[0]
    }

    const selectedOption = await this.interactor.chooseOption(availableIntegrations, 'Select integration to edit:')

    return selectedOption
  }

  /**
   * Edit integration configuration with context display
   */
  private async editIntegrationWithContext(
    integrationName: string,
    currentConfig: IntegrationConfig,
    mergedConfig: IntegrationLocalConfig,
    targetLevel: ConfigLevel
  ): Promise<IntegrationConfig> {
    const editedConfig: IntegrationConfig = {}

    // Show current configuration context
    this.showConfigurationContext(integrationName, targetLevel)

    // Edit API URL
    const apiUrl = await this.editPropertyWithContext(
      'API URL',
      currentConfig.apiUrl,
      mergedConfig[integrationName]?.apiUrl,
      targetLevel
    )
    if (apiUrl) {
      editedConfig.apiUrl = apiUrl
    }

    // Edit Username
    const username = await this.editPropertyWithContext(
      'Username',
      currentConfig.username,
      mergedConfig[integrationName]?.username,
      targetLevel
    )
    if (username) {
      editedConfig.username = username
    }

    // Edit API Key (special handling for sensitive data)
    const apiKey = await this.editApiKeyWithContext(
      currentConfig.apiKey,
      mergedConfig[integrationName]?.apiKey,
      targetLevel
    )
    if (apiKey !== undefined) {
      editedConfig.apiKey = apiKey
    }

    return editedConfig
  }

  /**
   * Show configuration context
   */
  private showConfigurationContext(integrationName: string, targetLevel: ConfigLevel): void {
    const levelName = targetLevel.toLowerCase()
    const contextMessage = `
# Editing Integration Configuration

**Integration:** ${integrationName}
**Level:** ${levelName}

## Current Context

The following shows current values at different levels:
- **PROJECT level**: Values from project configuration
- **USER level**: Values from user configuration
- **MERGED**: Final effective values after merging
`
    this.interactor.displayText(contextMessage)
  }

  /**
   * Edit a property with context display
   */
  private async editPropertyWithContext(
    propertyName: string,
    currentValue: string | undefined,
    mergedValue: string | undefined,
    targetLevel: ConfigLevel
  ): Promise<string | undefined> {
    // Get context values
    const projectConfig = this.service.getIntegrations(ConfigLevel.PROJECT)
    const userConfig = this.service.getIntegrations(ConfigLevel.USER)

    // Build context display
    const contextLines: string[] = []
    contextLines.push(`## ${propertyName}`)
    contextLines.push('')

    // Show values at each level
    const projectValue = Object.values(projectConfig)[0]?.[propertyName as keyof IntegrationConfig]
    const userValue = Object.values(userConfig)[0]?.[propertyName as keyof IntegrationConfig]

    contextLines.push(`- **PROJECT:** ${projectValue ? `\`${projectValue}\`` : '*[not set]*'}`)

    const marker = targetLevel === ConfigLevel.USER ? ' **← editing this level**' : ''
    contextLines.push(`- **USER:** ${userValue ? `\`${userValue}\`` : '*[not set]*'}${marker}`)

    if (targetLevel === ConfigLevel.PROJECT) {
      contextLines.push(
        `- **PROJECT:** ${currentValue ? `\`${currentValue}\`` : '*[not set]*'} **← editing this level**`
      )
    }

    contextLines.push(`- **MERGED:** ${mergedValue ? `\`${mergedValue}\`` : '*[not set]*'}`)
    contextLines.push('')
    contextLines.push(`Enter ${propertyName.toLowerCase()}:`)

    const prompt = contextLines.join('\n')
    const result = await this.interactor.promptText(prompt, currentValue || '')

    return result.trim() || undefined
  }

  /**
   * Edit API key with special handling for sensitive data
   */
  private async editApiKeyWithContext(
    currentApiKey: string | undefined,
    mergedApiKey: string | undefined,
    targetLevel: ConfigLevel
  ): Promise<string | undefined> {
    // Get context values (masked)
    const projectConfig = this.service.getIntegrations(ConfigLevel.PROJECT)
    const userConfig = this.service.getIntegrations(ConfigLevel.USER)

    // Build context display
    const contextLines: string[] = []
    contextLines.push(`## API Key`)
    contextLines.push('')

    // Show masked values at each level
    const projectHasKey = Object.values(projectConfig)[0]?.apiKey
    const userHasKey = Object.values(userConfig)[0]?.apiKey

    contextLines.push(`- **PROJECT:** ${projectHasKey ? '`Set (masked)`' : '*[not set]*'}`)

    const marker = targetLevel === ConfigLevel.USER ? ' **← editing this level**' : ''
    contextLines.push(`- **USER:** ${userHasKey ? '`Set (masked)`' : '*[not set]*'}${marker}`)

    if (targetLevel === ConfigLevel.PROJECT) {
      contextLines.push(`- **PROJECT:** ${currentApiKey ? '`Set (masked)`' : '*[not set]*'} **← editing this level**`)
    }

    contextLines.push(`- **MERGED:** ${mergedApiKey ? '`Set (masked)`' : '*[not set]*'}`)
    contextLines.push('')

    // Special prompt for API key
    if (currentApiKey) {
      contextLines.push(`Current API key is masked for security.`)
      contextLines.push(`- Enter new API key to update`)
      contextLines.push(`- Enter \`${MASKED_VALUE}\` to keep current value`)
      contextLines.push(`- Leave empty to remove API key`)
    } else {
      contextLines.push(`Enter API key (leave empty if not needed):`)
    }

    const prompt = contextLines.join('\n')
    const defaultValue = currentApiKey ? MASKED_VALUE : ''
    const apiKeyInput = await this.interactor.promptText(prompt, defaultValue)

    if (apiKeyInput === MASKED_VALUE) {
      // Keep existing API key
      return currentApiKey
    } else if (apiKeyInput === '') {
      // Remove API key
      return undefined
    } else {
      // New API key provided
      return apiKeyInput
    }
  }
}
