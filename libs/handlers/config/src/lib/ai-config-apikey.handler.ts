import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model'
import { AiProviderConfig } from '@coday/model'

/**
 * Handler for setting only the API key of an existing AI provider.
 * Prompts the user for provider selection and apiKey update.
 * Supports --project/-p for level selection.
 */
export class AiConfigApikeyHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'apikey',
      description:
        'Set or update the API key for an existing AI provider. User level is default, use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Determine config level (user/project)
    const args = parseArgs(command, [{ key: 'project', alias: 'p' }])
    const isProject = !!args.project
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Build selection list from merged config
    const mergedConfig = this.services.aiConfig.getMergedConfiguration()
    if (!mergedConfig.providers.length) {
      this.interactor.displayText('No AI providers found at any level.')
      return context
    }

    // Present merged provider name list
    const providerNames = mergedConfig.providers.map((p) => p.name)
    const chosenName = await this.interactor.chooseOption(
      providerNames,
      `Select provider to set API key for (will create an override at ${level} level if not present):`
    )
    if (!chosenName) return context

    // Get config at desired level, or clone from merged if missing
    let editConfig: AiProviderConfig | undefined = this.services.aiConfig.getProvider(chosenName, level)
    if (!editConfig) {
      const merged = mergedConfig.providers.find((p) => p.name === chosenName)
      if (!merged) {
        this.interactor.displayText('Internal error: Provider not found in merged config.')
        return context
      }
      editConfig = { name: chosenName }
    } else {
      editConfig = { ...editConfig }
    }

    // Prompt only for API key (masked input)
    editConfig.apiKey = await this.interactor.promptSecretText(
      'API key for this provider (leave blank to remove, or keep masked value to retain current):',
      editConfig.apiKey
    )

    // Save (excluding models!) at this level
    await this.services.aiConfig.saveProvider(editConfig, level)
    this.interactor.displayText(`✅ API key for provider '${editConfig.name}' updated at ${level} level.`)
    return context
  }
}
