import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { ConfigLevel } from '@coday/model/config-level'

export class IntegrationDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: IntegrationConfigService
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete an integration configuration. Use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProjectLevel = !!args.project
    const searchTerm = args.rest.trim().toUpperCase()

    // Find matching integrations
    const level = isProjectLevel ? ConfigLevel.PROJECT : ConfigLevel.USER
    const allIntegrations = this.service.getIntegrations(level)
    const integrationNames = Object.keys(allIntegrations)

    const matching = searchTerm ? integrationNames.filter((name) => name.includes(searchTerm)) : integrationNames

    if (matching.length === 0) {
      const levelName = isProjectLevel ? 'project' : 'user'
      if (searchTerm) {
        this.interactor.warn(`No integrations found matching '${searchTerm}' at ${levelName} level.`)
      } else {
        const addCommand = `config integration add${isProjectLevel ? ' --project' : ''}`
        this.interactor.displayText(
          `No integrations configured at ${levelName} level. Use '${addCommand}' to add an integration.`
        )
      }
      return context
    }

    let integrationName: string | undefined

    // If multiple integrations match, choose one
    if (matching.length > 1) {
      integrationName = await this.interactor.chooseOption(matching.sort(), 'Select the integration to delete:')
    } else {
      // Single integration match
      integrationName = matching[0]
    }

    // If no integration was selected
    if (!integrationName) {
      this.interactor.displayText('No integration selected for deletion.')
      return context
    }

    // Get the configuration details for confirmation
    const integrationConfig = allIntegrations[integrationName]

    // Confirm removal
    const levelName = isProjectLevel ? 'project' : 'user'
    const confirmMessage = `
# ⚠️ Confirm Deletion

**Integration:** ${integrationName}
**Level:** ${levelName}
**Configuration:**
- API URL: ${integrationConfig?.apiUrl || 'Not set'}
- Username: ${integrationConfig?.username || 'Not set'}
- API Key: ${integrationConfig?.apiKey ? 'Set' : 'Not set'}

Are you sure you want to delete this integration configuration?
`
    const confirmAnswer = await this.interactor.chooseOption(
      ['no, keep', 'delete'],
      'Confirm deletion:',
      confirmMessage
    )

    if (confirmAnswer.toLowerCase() !== 'delete') {
      this.interactor.displayText('Deletion cancelled.')
      return context
    }

    try {
      // Remove the integration
      await this.service.deleteIntegration(integrationName, level)

      const successMessage = `
# ✅ Integration Deleted

**Integration:** ${integrationName}
**Level:** ${levelName}
**Status:** Successfully removed from configuration
`
      this.interactor.displayText(successMessage)
    } catch (error) {
      this.interactor.error(`Failed to delete integration: ${error instanceof Error ? error.message : String(error)}`)
    }

    return context
  }
}
