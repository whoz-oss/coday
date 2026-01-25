import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model/config-level'

/**
 * Handler for deleting a model from a provider config.
 */
export class AiModelDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'delete',
      description:
        'Delete a model from an AI provider configuration. Use --provider=name, --model=name, --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Parse arguments using parseArgs
    const subCommand = this.getSubCommand(command)
    const args = parseArgs(subCommand, [
      { key: 'provider' },
      { key: 'model', alias: 'm' },
      { key: 'project', alias: 'p' },
    ])
    const isProject = !!args.project
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER
    const providerName = typeof args.provider === 'string' ? args.provider : undefined
    const modelName = typeof args.model === 'string' ? args.model : undefined

    // Step 1: Get providers at level (exact match)
    const providers = this.services.aiConfig.getProviders(level)
    if (!providers.length) {
      this.interactor.displayText(`No AI providers found at ${level} level.`)
      return context
    }
    const providerNames = providers.map((p) => p.name)
    let provider = providerName ? providers.find((p) => p.name === providerName) : undefined
    if (!provider) {
      // Prompt if not matched
      const chosen = await this.interactor.chooseOption(
        providerNames,
        `Select provider to delete a model from at ${level} level:`
      )
      if (!chosen) return context
      provider = providers.find((p) => p.name === chosen)
    }
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Get model from provider (exact match)
    const models = provider.models || []
    if (!models.length) {
      this.interactor.displayText(`Provider '${provider.name}' has no models to delete.`)
      return context
    }
    const modelNames = models.map((m) => m.name)
    let model = modelName ? models.find((m) => m.name === modelName) : undefined
    if (!model) {
      // Prompt if not matched
      const chosen = await this.interactor.chooseOption(
        modelNames,
        `Select model to delete from provider '${provider.name}':`
      )
      if (!chosen) return context
      model = models.find((m) => m.name === chosen)
    }
    if (!model) {
      this.interactor.error('Selected model not found.')
      return context
    }

    // Step 3: Confirm deletion
    const confirmChoice = await this.interactor.chooseOption(
      ['yes', 'no'],
      `Are you sure you want to delete model '${model.name}' from provider '${provider.name}' at ${level} level? This action cannot be undone.`
    )
    if (confirmChoice !== 'yes') {
      this.interactor.displayText('Model deletion cancelled.')
      return context
    }

    // Step 4: Delete the model and save
    const updatedModels = models.filter((m) => m.name !== model.name)
    await this.services.aiConfig.saveProvider(
      {
        ...provider,
        models: updatedModels,
      },
      level
    )

    this.interactor.displayText(`✅ Model '${model.name}' deleted from provider '${provider.name}' at ${level} level.`)
    return context
  }
}
