import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'
import { parseArgs } from '../parse-args'

/**
 * Handler for deleting a webhook configuration.
 */
export class WebhookDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete a webhook configuration. Use --uuid=value',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Check if webhook service exists
    if (!this.services.webhook) {
      this.interactor.error('Webhook service is not available')
      return context
    }

    try {
      // Parse arguments to get uuid
      const args = parseArgs(this.getSubCommand(command), [
        { key: 'uuid' }
      ])

      let uuid = args.uuid as string

      // If no uuid provided, show list of webhooks to select from
      if (!uuid) {
        const webhooks = await this.services.webhook.list()
        
        if (webhooks.length === 0) {
          this.interactor.displayText('No webhooks found to delete.')
          return context
        }

        const webhookOptions = webhooks.map(w => `${w.name} (${w.uuid}) - Project: ${w.project}`)
        const options = [...webhookOptions, 'Cancel']
        
        const chosen = await this.interactor.chooseOption(
          options,
          'Select webhook to delete:'
        )
        
        if (!chosen || chosen === 'Cancel') {
          this.interactor.displayText('Webhook deletion cancelled.')
          return context
        }
        
        // Extract UUID from selection
        const match = chosen.match(/\(([^)]+)\)/)
        uuid = match ? match[1] : ''
      }

      if (!uuid) {
        this.interactor.error('UUID is required')
        return context
      }

      // Load the webhook to confirm it exists
      const webhook = await this.services.webhook.get(uuid)
      if (!webhook) {
        this.interactor.error(`Webhook with UUID '${uuid}' not found`)
        return context
      }

      // Ask for confirmation before deletion
      const confirmChoice = await this.interactor.chooseOption(
        ['Yes, delete this webhook', 'No, cancel deletion'],
        `Are you sure you want to delete webhook '${webhook.name}' (Project: ${webhook.project})?\n\nThis action cannot be undone.`
      )

      if (confirmChoice !== 'Yes, delete this webhook') {
        this.interactor.displayText('Webhook deletion cancelled.')
        return context
      }

      // Delete the webhook
      const deleted = await this.services.webhook.delete(uuid)
      
      if (deleted) {
        this.interactor.displayText(`✅ Webhook '${webhook.name}' deleted successfully`)
      } else {
        this.interactor.error('Failed to delete webhook')
      }

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.error(`Failed to delete webhook: ${errorMessage}`)
    }

    return context
  }
}