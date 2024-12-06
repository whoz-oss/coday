import { CommandContext, CommandHandler, ConcreteIntegrations, IntegrationConfig, Interactor } from '../../model'
import { configService } from '../../service/config.service'
import { integrationService } from '../../service/integration.service'
import { keywords } from '../../keywords'

export class EditIntegrationHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'edit-integration',
      description: 'Edit integration settings',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!configService.project) {
      this.interactor.displayText('No current project, select one first.')
      return context
    }

    // List all set integrations and prompt to choose one to edit (or type name of wanted one)
    const currentIntegrations = integrationService.integrations
    const existingIntegrationNames: string[] = currentIntegrations ? Object.keys(currentIntegrations) : []
    const answer = (
      await this.interactor.chooseOption(
        [...ConcreteIntegrations, keywords.exit],
        'Select an integration to edit',
        `Integrations are tools behind some commands and/or functions for AI.\nHere are the configured ones: (${existingIntegrationNames.join(', ')})`
      )
    ).toUpperCase()
    if (!answer || answer === keywords.exit.toUpperCase()) {
      return context
    }
    let apiIntegration: IntegrationConfig = currentIntegrations ? currentIntegrations[answer] || {} : {}
    let selectedName = answer

    // take all fields with existing values if available
    const apiUrl = await this.interactor.promptText('Api url (if applicable)', apiIntegration.apiUrl)
    const username = await this.interactor.promptText('username (if applicable)', apiIntegration.username)
    const apiKey = await this.interactor.promptText('Api key (if applicable)') // TODO see another way to update an api key ?

    integrationService.setIntegration(selectedName, { apiUrl, username, apiKey })

    return context
  }
}
