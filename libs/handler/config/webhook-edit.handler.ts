import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'
import { parseArgs } from '../parse-args'
import { Webhook } from '../../service/webhook.service'

/**
 * Handler for editing an existing webhook configuration.
 * Prompts the user property by property for webhook fields.
 */
export class WebhookEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit an existing webhook configuration. Use --uuid=value',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Check if webhook service exists
    if (!this.services.webhook) {
      this.interactor.error('Webhook service is not available')
      return context
    }

    // Parse arguments to get uuid
    const args = parseArgs(this.getSubCommand(command), [
      { key: 'uuid' }
    ])

    let uuid = args.uuid as string
    
    // If no uuid provided, show list and let user select
    if (!uuid) {
      try {
        const webhooks = await this.services.webhook.list()
        
        if (webhooks.length === 0) {
          this.interactor.displayText('No webhooks available to edit')
          return context
        }

        const webhookOptions = webhooks.map(w => `${w.name} (${w.uuid})`)
        const chosen = await this.interactor.chooseOption(
          webhookOptions,
          'Select webhook to edit:'
        )
        
        if (!chosen) return context
        
        // Extract UUID from selection
        const match = chosen.match(/\(([^)]+)\)$/)
        uuid = match ? match[1] : ''
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        this.interactor.error(`Failed to list webhooks: ${errorMessage}`)
        return context
      }
    }

    if (!uuid) {
      this.interactor.error('UUID is required')
      return context
    }

    try {
      // Load the webhook
      const webhook = await this.services.webhook.get(uuid)
      if (!webhook) {
        this.interactor.error(`Webhook with UUID '${uuid}' not found`)
        return context
      }

      // Create a copy for editing
      const editWebhook: Partial<Webhook> = { ...webhook }

      // Edit name (required)
      editWebhook.name = await this.interactor.promptText(
        'Webhook name (required):',
        webhook.name
      )

      if (!editWebhook.name?.trim()) {
        this.interactor.error('Webhook name is required')
        return context
      }

      // Edit project (required, show available projects)
      const availableProjects = this.services.project.projects
      if (availableProjects.length === 0) {
        this.interactor.error('No projects available. Create a project first.')
        return context
      }

      const projectOptions = [...availableProjects]
      const selectedProject = await this.interactor.chooseOption(
        projectOptions,
        `Select target project (current: ${webhook.project}):`
      )

      if (!selectedProject) {
        this.interactor.error('Project selection is required')
        return context
      }

      editWebhook.project = selectedProject

      // Edit commandType
      const commandTypeOptions = ['free', 'template']
      const selectedCommandType = await this.interactor.chooseOption(
        commandTypeOptions,
        `Command type (current: ${webhook.commandType}):\n- free: Direct commands from webhook payload\n- template: Predefined commands with placeholders`
      )

      if (!selectedCommandType) {
        this.interactor.error('Command type selection is required')
        return context
      }

      editWebhook.commandType = selectedCommandType as 'free' | 'template'

      // Edit commands (array of strings)
      const currentCommandsText = webhook.commands ? webhook.commands.join('\n') : ''
      const commandsText = await this.interactor.promptText(
        `Commands (one per line, leave empty for none):\nCurrent commands:\n${currentCommandsText || '(none)'}\n\nEnter new commands:`,
        currentCommandsText
      )

      // Parse commands from multi-line text
      if (commandsText.trim()) {
        editWebhook.commands = commandsText
          .split('\n')
          .map(cmd => cmd.trim())
          .filter(cmd => cmd.length > 0)
      } else {
        editWebhook.commands = []
      }

      // Save the updated webhook
      const updatedWebhook = await this.services.webhook.update(uuid, editWebhook)
      
      if (!updatedWebhook) {
        this.interactor.error('Failed to update webhook')
        return context
      }

      this.interactor.displayText(`✅ Webhook '${updatedWebhook.name}' updated successfully`)
      
      // Show summary of changes
      const summary = `
**Updated webhook details:**
- Name: ${updatedWebhook.name}
- UUID: ${updatedWebhook.uuid}
- Project: ${updatedWebhook.project}
- Type: ${updatedWebhook.commandType}
- Commands: ${updatedWebhook.commands?.length || 0} command${updatedWebhook.commands?.length === 1 ? '' : 's'}
`
      this.interactor.displayText(summary)

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.error(`Failed to edit webhook: ${errorMessage}`)
    }

    return context
  }
}