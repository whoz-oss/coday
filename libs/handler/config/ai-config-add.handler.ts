import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { AiConfigEditHandler } from './ai-config-edit.handler'
import { ConfigLevel } from '../../model/config-level'
import { AiProviderConfig } from '../../model/ai-provider-config'

/**
 * Handler for adding a new AI provider configuration.
 * Prompts for provider name, creates default config, then redirects to edit handler.
 * Needs a reference to the edit handler to avoid code duplication.
 */
export class AiConfigAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices,
    private editHandler: AiConfigEditHandler,
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new AI provider configuration and edit it.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Determine config level (user/project)
    const lowerCmd = command.toLowerCase()
    const isProject = lowerCmd.includes(' --project') || lowerCmd.includes(' -p')
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Get merged configuration to check for duplicates
    const mergedConfig = this.services.aiConfig.getMergedConfiguration()

    // Prompt for provider name
    const newProviderName = await this.interactor.promptText(
      'Enter a unique name for the new AI provider configuration:'
    )

    // Check for duplicate names across all levels
    const isDuplicate = mergedConfig.providers.some((p) => p.name.toLowerCase() === newProviderName.toLowerCase())
    if (isDuplicate) {
      this.interactor.warn(`Provider name '${newProviderName}' already exists. Cannot add duplicate.`)
      return context
    }

    // Create default config object
    const defaultConfig: AiProviderConfig = {
      name: newProviderName,
      type: 'openai', // Default type
      models: [{ 
        name: 'SMALL', 
        alias: 'default',
        contextWindow: 32000
      }]
    }

    // Save the default config at the specified level
    await this.services.aiConfig.saveProvider(defaultConfig, level)

    // Redirect to edit handler to complete configuration
    return this.editHandler.handle(`edit ${newProviderName} ${isProject ? '--project' : ''}`, context)
  }
}
