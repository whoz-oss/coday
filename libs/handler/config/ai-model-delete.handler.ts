import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

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
      description: 'Delete a model from an AI provider configuration.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Parse arguments
    const args = this.getSubCommand(command)
    let parsedArgs
    try {
      // Use the same utility as the other handlers
      const { parseAiModelHandlerArgs } = await import('./parse-ai-model-handler-args')
      parsedArgs = parseAiModelHandlerArgs(args)
    } catch (err: any) {
      this.interactor.error(err.message)
      return context
    }
    const isProject = parsedArgs.isProject
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Step 1: Get providers at level
    const providers = this.services.aiConfig.getProviders(level)
    if (!providers.length) {
      this.interactor.displayText(`No AI providers found at ${level} level.`)
      return context
    }
    const providerNames = providers.map(p => p.name)
    let providerNameInput = parsedArgs.aiProviderNameStart
    let provider = providerNameInput
      ? providers.find(p => p.name.toLowerCase().startsWith(providerNameInput.toLowerCase()))
      : undefined
    if (!provider) {
      // Prompt if not matched
      const providerName = await this.interactor.chooseOption(
        providerNames,
        `Select provider to delete a model from at ${level} level:`
      )
      if (!providerName) return context
      provider = providers.find(p => p.name === providerName)
    }
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Get model from provider
    const models = provider.models || []
    if (!models.length) {
      this.interactor.displayText(`Provider '${provider.name}' has no models to delete.`)
      return context
    }
    const modelNames = models.map(m => m.name)
    let modelNameInput = parsedArgs.aiModelName
    let model = modelNameInput
      ? models.find(m => m.name.toLowerCase().startsWith(modelNameInput.toLowerCase()))
      : undefined
    if (!model) {
      // Prompt if not matched
      const modelName = await this.interactor.chooseOption(
        modelNames,
        `Select model to delete from provider '${provider.name}':`
      )
      if (!modelName) return context
      model = models.find(m => m.name === modelName)
    }
    if (!model) {
      this.interactor.error('Selected model not found.')
      return context
    }

    // Step 3: Confirm deletion
    const confirm = await this.interactor.confirm(
      `Are you sure you want to delete model '${model.name}' from provider '${provider.name}' at ${level} level? This action cannot be undone.`,
      false
    )
    if (!confirm) {
      this.interactor.displayText('Model deletion cancelled.')
      return context
    }

    // Step 4: Delete the model and save
    const updatedModels = models.filter(m => m.name !== model.name)
    await this.services.aiConfig.saveProvider(
      {
        ...provider,
        models: updatedModels
      },
      level
    )

    this.interactor.displayText(`✅ Model '${model.name}' deleted from provider '${provider.name}' at ${level} level.`)
    return context
  }
}

