import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'
import { WebhookEditHandler } from './webhook-edit.handler'
import { Webhook } from '../../service/webhook.service'

/**
 * Handler for adding a new webhook configuration.
 * Prompts for webhook name, creates default config, then redirects to edit handler.
 * Needs a reference to the edit handler to avoid code duplication.
 */
export class WebhookAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices,
    private editHandler: WebhookEditHandler
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new webhook configuration',
    })
  }

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    // Check if webhook service exists
    if (!this.services.webhook) {
      this.interactor.error('Webhook service is not available')
      return context
    }

    try {
      // Create new webhook with default values
      const defaultWebhook: Omit<Webhook, 'uuid' | 'createdAt'> = {
        name: '',
        project: '', // Will be set in edit handler
        createdBy: this.services.user.username,
        commandType: 'free', // Default type
        commands: [], // Empty array initially
      }

      // Create the webhook
      const newWebhook = await this.services.webhook.create(defaultWebhook)

      this.interactor.displayText(`✅ Webhook created with UUID: ${newWebhook.uuid}`)

      // Delegate to edit handler to complete configuration
      return this.editHandler.handle(`edit --uuid=${newWebhook.uuid}`, context)
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.error(`Failed to create webhook: ${errorMessage}`)
      return context
    }
  }
}
