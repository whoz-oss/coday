import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { IntegrationEditHandler } from './integration-edit.handler'
import { ConfigLevel } from '@coday/model/config-level'
import { ConcreteIntegrations } from '@coday/model/integrations'
import { IntegrationConfig } from '@coday/model/integration-config'

/**
 * Handler for adding a new integration configuration.
 * Creates a default config and delegates to the edit handler for completion.
 * Follows the add-edit delegation pattern to avoid code duplication.
 */
export class IntegrationAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: IntegrationConfigService,
    private editHandler: IntegrationEditHandler
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new integration configuration. User level is default, use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProject = !!args.project
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Get merged configuration to check for existing integrations
    const mergedConfig = this.service.getMergedIntegrations()
    const existingIntegrations = Object.keys(mergedConfig)

    // Show available integrations to add
    const availableToAdd = ConcreteIntegrations.filter((integration) => !existingIntegrations.includes(integration))

    if (availableToAdd.length === 0) {
      this.interactor.displayText(
        `All available integrations (${ConcreteIntegrations.join(', ')}) are already configured.`
      )
      return context
    }

    // Let user choose which integration to add
    const selectedIntegration = await this.interactor.chooseOption(
      availableToAdd.sort(),
      'Select integration to add:',
      `Available integrations to configure:\n\n${availableToAdd.map((name) => `- **${name}**`).join('\n')}\n\nChoose an integration:`
    )

    if (!selectedIntegration) {
      this.interactor.displayText('No integration selected.')
      return context
    }

    // Create minimal default config (empty, will be filled in edit handler)
    const defaultConfig: IntegrationConfig = {}

    // Save the default config at the specified level
    await this.service.saveIntegration(selectedIntegration, defaultConfig, level)

    const levelName = isProject ? 'project' : 'user'
    const successMessage = `
# ✅ Integration Created

**Integration:** ${selectedIntegration}
**Level:** ${levelName}
**Status:** Default configuration created

Now completing the configuration...
`
    this.interactor.displayText(successMessage)

    // Delegate to edit handler to complete configuration
    const editCommand = `edit ${selectedIntegration} ${isProject ? '--project' : ''}`
    return this.editHandler.handle(editCommand, context)
  }
}
