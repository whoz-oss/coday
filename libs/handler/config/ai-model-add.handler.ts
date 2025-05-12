import { CommandContext, CommandHandler } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { AiModelEditHandler } from './ai-model-edit.handler'
import { ConfigLevel } from '../../model/config-level'
import { AiModel } from '../../model/ai-model'
import { parseAiModelHandlerArgs } from './parse-ai-model-handler-args'

/**
 * Handler for adding a new model to an AI provider config.
 * Prompts for model name, creates default, then redirects to edit handler.
 * Needs a reference to edit handler to avoid code duplication.
 */
export class AiModelAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices,
    private editHandler: AiModelEditHandler
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new model to an AI provider configuration and edit it.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Parse arguments using getSubCommand and parseAiModelHandlerArgs
    const args = this.getSubCommand(command)
    let parsedArgs
    try {
      parsedArgs = parseAiModelHandlerArgs(args)
    } catch (err: any) {
      this.interactor.error(err.message)
      return context
    }
    const isProject = parsedArgs.isProject
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Step 1: Choose provider (use arg if present)
    const providers = this.services.aiConfig.getProviders(level)
    if (!providers.length) {
      this.interactor.displayText(`No AI providers found at ${level} level.`)
      return context
    }
    const providerNames = providers.map((p) => p.name)
    let providerName = parsedArgs.aiProviderNameStart
    if (!providerName || !providerNames.includes(providerName)) {
      providerName = await this.interactor.chooseOption(
        providerNames,
        `Select provider to add a model at ${level} level:`
      )
      if (!providerName) return context
    }
    const provider = providers.find((p) => p.name === providerName)
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Get model name (use arg if present)
    let modelName = parsedArgs.aiModelName
    if (!modelName) {
      modelName = await this.interactor.promptText('Enter a unique model name (as per API):')
      if (!modelName || !modelName.trim()) {
        this.interactor.warn('Model name cannot be empty.')
        return context
      }
    }
    const models = provider.models || []
    const duplicate = models.find(
      (m) =>
        m.name.toLowerCase() === modelName.toLowerCase() ||
        (m.alias && m.alias.toLowerCase() === modelName.toLowerCase())
    )
    if (duplicate) {
      this.interactor.warn(`A model with the name or alias '${modelName}' already exists for this provider.`)
      return context
    }

    // Step 3: Create default model
    const defaultModel: AiModel = {
      name: modelName,
      contextWindow: 32000,
    }

    // Step 4: Save new model
    await this.services.aiConfig.saveModel(providerName, defaultModel, level)

    // Step 5: Redirect to edit handler for full configuration; pass all arguments to preselect
    let editCmd = `edit ${providerName} ${modelName}`
    if (isProject) editCmd += ' --project'
    return this.editHandler.handle(editCmd.trim(), context)
  }
}

