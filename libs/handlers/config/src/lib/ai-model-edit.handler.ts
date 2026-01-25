import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model/config-level'
import { AiModel } from '@coday/model/ai-model'

/**
 * Handler for editing a single model of a provider config at a precise level (default user).
 * Interactive, property-by-property editing.
 */
export class AiModelEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'edit',
      description:
        'Edit a model of an AI provider configuration. Use --provider=name, --model=name, --project/-p for project level.',
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
        `Select provider to edit its model at ${level} level:`
      )
      if (!chosen) return context
      provider = providers.find((p) => p.name === chosen)
    }
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Choose model (exact match)
    const models = provider.models || []
    if (!models.length) {
      this.interactor.displayText(`Provider '${provider.name}' has no models to edit.`)
      return context
    }
    const modelNames = models.map((m) => m.name)
    let model = modelName ? models.find((m) => m.name === modelName) : undefined
    if (!model) {
      // Prompt if not found or not provided
      const chosen = await this.interactor.chooseOption(
        modelNames,
        `Select model to edit for provider '${provider.name}':`
      )
      if (!chosen) return context
      model = models.find((m) => m.name === chosen)
    }
    if (!model) {
      this.interactor.error('Selected model not found.')
      return context
    }

    // Step 3: Edit properties
    // Name
    const newName = await this.interactor.promptText('Model name (unique, as per API):', model.name)
    // Alias
    const newAlias = await this.interactor.promptText('Alias (project-friendly name, optional):', model.alias || '')
    // contextWindow
    let newContextWindowRaw = await this.interactor.promptText(
      'Context window (number of tokens):',
      String(model.contextWindow)
    )
    let newContextWindow = parseInt(newContextWindowRaw, 10)
    if (isNaN(newContextWindow) || newContextWindow <= 0) {
      this.interactor.warn('Invalid value for context window. Keeping previous value.')
      newContextWindow = model.contextWindow
    }
    // price (optional, object)
    let price = model.price ? { ...model.price } : {}
    for (const priceKey of ['inputMTokens', 'outputMTokens', 'cacheWrite', 'cacheRead']) {
      const key = priceKey as keyof typeof price
      let pricePrompt = await this.interactor.promptText(
        `Price ${key} (per million tokens, optional):`,
        price[key] !== undefined ? String(price[key]) : ''
      )
      if (pricePrompt && pricePrompt.trim()) {
        const parsed = parseFloat(pricePrompt)
        if (!isNaN(parsed)) price[key] = parsed
        else this.interactor.warn(`Invalid number for price ${key}, keeping previous.`)
      } else {
        delete price[key]
      }
    }
    if (Object.keys(price).length === 0) {
      price = {}
    }

    // Construct updated model
    const updatedModel: AiModel = {
      ...model,
      name: newName,
      alias: newAlias || undefined,
      contextWindow: newContextWindow,
      price: price,
    }

    // Save
    await this.services.aiConfig.saveModel(provider.name, updatedModel, level)
    this.interactor.displayText(`✅ Model '${updatedModel.name}' updated for provider '${provider}' at ${level} level.`)
    return context
  }
}
