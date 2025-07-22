import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'

/**
 * Handler for listing all configured webhooks.
 */
export class WebhookListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'list',
      description: 'List all configured webhooks',
    })
  }

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    // Check if webhook service exists
    if (!this.services.webhook) {
      this.interactor.error('Webhook service is not available')
      return context
    }

    try {
      // Get all webhooks
      const webhooks = await this.services.webhook.list()

      if (webhooks.length === 0) {
        this.interactor.displayText('No webhooks configured')
        return context
      }

      // Format and display webhooks
      let output = `Found ${webhooks.length} webhook${webhooks.length === 1 ? '' : 's'}:\n\n`

      webhooks.forEach((webhook, index) => {
        const createdAtFormatted = webhook.createdAt.toLocaleString()
        const commandsInfo = webhook.commands && webhook.commands.length > 0 
          ? `${webhook.commands.length} command${webhook.commands.length === 1 ? '' : 's'}` 
          : 'no commands'

        output += `${index + 1}. **${webhook.name}**\n`
        output += `   UUID: \`${webhook.uuid}\`\n`
        output += `   Project: ${webhook.project}\n`
        output += `   Created by: ${webhook.createdBy}\n`
        output += `   Created: ${createdAtFormatted}\n`
        output += `   Type: ${webhook.commandType}\n`
        output += `   Commands: ${commandsInfo}\n\n`
      })

      this.interactor.displayText(output)
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.error(`Failed to list webhooks: ${errorMessage}`)
    }

    return context
  }
}