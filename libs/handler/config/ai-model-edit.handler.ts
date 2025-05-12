import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { ConfigLevel } from '../../model/config-level'
import { AiModel } from '../../model/ai-model'
import { parseAiModelHandlerArgs } from './parse-ai-model-handler-args'

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
      description: 'Edit a model of an AI provider configuration (default user level).',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Parse arguments after commandWord ("edit")
    const args = command.trim().replace(/^edit\b/i, '').trim()
    let parsedArgs
    try {
      parsedArgs = parseAiModelHandlerArgs(args)
    } catch (err: any) {
      this.interactor.error(err.message)
      return context
    }
    const isProject = parsedArgs.isProject
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Step 1: Choose provider, use arg if present
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
        `Select provider to edit its model at ${level} level:`
      )
      if (!providerName) return context
    }
    const provider = providers.find((p) => p.name === providerName)
    if (!provider) {
      this.interactor.error('Selected provider not found at this level.')
      return context
    }

    // Step 2: Choose model, use arg if present
    const models = provider.models || []
    if (!models.length) {
      this.interactor.displayText(`Provider '${providerName}' has no models to edit.`)
      return context
    }
    const modelNames = models.map((m) => m.name)
    let modelName = parsedArgs.aiModelName
    if (!modelName || !modelNames.includes(modelName)) {
      modelName = await this.interactor.chooseOption(
        modelNames,
        `Select model to edit for provider '${providerName}':`
      )
      if (!modelName) return context
    }
    const model = models.find((m) => m.name === modelName)
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
    let newContextWindowRaw = await this.interactor.promptText('Context window (number of tokens):', String(model.contextWindow))
    let newContextWindow = parseInt(newContextWindowRaw, 10)
    if (isNaN(newContextWindow) || newContextWindow <= 0) {
      this.interactor.warn('Invalid value for context window. Keeping previous value.')
      newContextWindow = model.contextWindow
    }
    // price (optional, object)
    let price = model.price ? { ...model.price } : {}
    for (const priceKey of ['inputMTokens', 'outputMTokens', 'cacheWrite', 'cacheRead']) {
      let pricePrompt = await this.interactor.promptText(
        `Price ${priceKey} (per million tokens, optional):`,
        price[priceKey] !== undefined ? String(price[priceKey]) : ''
      )
      if (pricePrompt && pricePrompt.trim()) {
        const parsed = parseFloat(pricePrompt)
        if (!isNaN(parsed)) price[priceKey] = parsed
        else this.interactor.warn(`Invalid number for price ${priceKey}, keeping previous.`)
      } else {
        delete price[priceKey]
      }
    }
    if (Object.keys(price).length === 0) price = undefined

    // Construct updated model
    const updatedModel: AiModel = {
      ...model,
      name: newName,
      alias: newAlias || undefined,
      contextWindow: newContextWindow,
      price: price,
    }

    // Save
    await this.services.aiConfig.saveModel(providerName, updatedModel, level)
    this.interactor.displayText(`✅ Model '${updatedModel.name}' updated for provider '${providerName}' at ${level} level.`)
    return context
  }
}
