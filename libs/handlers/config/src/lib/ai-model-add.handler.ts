import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { AiModelEditHandler } from './ai-model-edit.handler'
import { ConfigLevel } from '@coday/model/config-level'
import { AiModel } from '@coday/model/ai-model'

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
      description:
        'Add a new model to an AI provider configuration. Use --provider=name, --model=name (optional), --project/-p for project level.',
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

    // Step 1: Choose provider (exact match)
    const providers = this.services.aiConfig.getProviders(level)
    if (!providers.length) {
      this.interactor.displayText(`No AI providers found at ${level} level.`)
      return context
    }
    const providerNames = providers.map((p) => p.name)
    let provider = providerName ? providers.find((p) => p.name === providerName) : undefined
    if (!provider) {
      // If not matched, prompt interactively
      const chosen = await this.interactor.chooseOption(
        providerNames,
        `Select provider to add a model at ${level} level:`
      )
      if (!chosen) return context
      provider = providers.find((p) => p.name === chosen)
    }
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Get model name (exact match)
    let _modelName = modelName
    if (!_modelName) {
      _modelName = await this.interactor.promptText('Enter a unique model name (as per API):')
      if (!_modelName || !_modelName.trim()) {
        this.interactor.warn('Model name cannot be empty.')
        return context
      }
    }
    const models = provider.models || []
    const duplicate = models.find((m) => m.name === _modelName || m.alias === _modelName)
    if (duplicate) {
      this.interactor.warn(`A model with the name or alias '${_modelName}' already exists for this provider.`)
      return context
    }

    // Step 3: Create default model
    const defaultModel: AiModel = {
      name: _modelName,
      contextWindow: 32000,
    }

    // Step 4: Save new model
    await this.services.aiConfig.saveModel(provider.name, defaultModel, level)

    // Step 5: Redirect to edit handler for full configuration
    let editCmd = `${this.editHandler.commandWord} --provider=${provider.name} --model=${_modelName}`
    if (isProject) editCmd += ' --project'
    return this.editHandler.handle(editCmd.trim(), context)
  }
}
