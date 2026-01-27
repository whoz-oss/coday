import { CommandHandler } from '@coday/handler'
import { CommandContext, Interactor } from '@coday/model'
import { EmptyUsage } from '@coday/model'

export class CostLimitHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'cost',
      description: 'Configure the cost limit threshold for AI operations',
    })
  }

  async handle(_: string, context: CommandContext): Promise<CommandContext> {
    // Get current cost limit (from thread if exists, otherwise from default)
    const currentLimit = context.aiThread?.usage.priceThreshold ?? EmptyUsage.priceThreshold

    // Prompt for new cost limit with current value as default
    const input = await this.interactor.promptText(
      `Cost limit in dollars (${currentLimit.toFixed(2)}):`,
      currentLimit.toString()
    )

    // Validate input
    const newLimit = parseFloat(input.trim())
    if (isNaN(newLimit) || newLimit <= 0) {
      this.interactor.error('Invalid cost limit. Please enter a positive number or 0.')
      return context
    }

    // Update current thread if it exists
    if (context.aiThread) {
      context.aiThread.usage.priceThreshold = newLimit
    }

    this.interactor.displayText(`✅ Cost limit updated to $${newLimit.toFixed(2)}`)

    return context
  }
}
