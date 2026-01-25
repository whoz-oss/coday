import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model/config-level'
import { AiProviderConfig, AiProviderType } from '@coday/model/ai-provider-config'

/**
 * Handler for editing an existing AI provider configuration (not models).
 * Prompts the user property by property. Supports --project/-p for level selection.
 * If the provider only exists at a lower level, creates an override at the current level for editing.
 */
export class AiConfigEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'edit',
      description:
        'Edit an existing AI provider configuration (excluding models). User level is default, use --project/-p for project level.',
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

    // Build selection list from merged config: always allow editing
    const mergedConfig = this.services.aiConfig.getMergedConfiguration()
    if (!mergedConfig.providers.length) {
      this.interactor.displayText('No AI providers found at any level.')
      return context
    }

    // Present merged provider name list
    const providerNames = mergedConfig.providers.map((p) => p.name)
    const chosenName = await this.interactor.chooseOption(
      providerNames,
      `Select provider to edit (will create an override at ${level} level if not present):`
    )
    if (!chosenName) return context

    // Get config at desired level
    let editConfig: AiProviderConfig | undefined = this.services.aiConfig.getProvider(chosenName, level)
    if (!editConfig) {
      // Not present at this level: copy from merged (deep clone, excluding models)
      const merged = mergedConfig.providers.find((p) => p.name === chosenName)
      if (!merged) {
        this.interactor.displayText('Internal error: Provider not found in merged config.')
        return context
      }
      editConfig = { ...merged }
      // Remove models, as they aren't edited here
      delete editConfig.models
    } else {
      // Clone to avoid mutating underlying config directly (until save)
      editConfig = { ...editConfig }
    }

    // Prompt for each field except models, with explanations
    // Name
    editConfig.name = await this.interactor.promptText(
      'Provider name (unique, e.g. openai, anthropic, or a custom string):',
      editConfig.name
    )

    // Type
    const choosenType = await this.interactor.chooseOption(
      ['inherit', 'openai', 'anthropic'],
      'Provider type (openai, anthropic, or leave blank for custom):\nUsed for default parameters and special handling for well-known providers.'
    )
    if (choosenType !== 'inherit') {
      editConfig.type = choosenType as AiProviderType
    }

    // URL
    editConfig.url =
      (await this.interactor.promptText(
        'Endpoint URL for this provider (optional, only for custom/self-hosted):',
        editConfig.url || ''
      )) || undefined

    // API Key (masked input)
    editConfig.apiKey = await this.interactor.promptSecretText(
      'API key for this provider (leave blank to remove, or keep masked value to retain current):',
      editConfig.apiKey
    )

    // Secure flag
    const secureOptions = ['no', 'yes']
    const securePrompt = 'Is this provider secure (local, non-cloud, no data leaves infra)? yes/no:'
    const secureChoice = await this.interactor.chooseOption(
      secureOptions,
      securePrompt,
      editConfig.secure ? 'yes' : 'no'
    )
    editConfig.secure = secureChoice === 'yes'

    // Save (excluding models!) at this level
    await this.services.aiConfig.saveProvider(editConfig, level)
    this.interactor.displayText(`✅ Provider '${editConfig.name}' updated at ${level} level.`)
    return context
  }
}
