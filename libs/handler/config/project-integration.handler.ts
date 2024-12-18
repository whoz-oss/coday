import { CommandContext, CommandHandler, ConcreteIntegrations, IntegrationConfig } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { keywords } from '../../keywords'

export class ProjectIntegrationHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'project',
      description: 'Edit project-wide integration settings (affects all users)',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const project = this.services.project.selectedProject
    if (!project) {
      this.interactor.displayText('No current project, select one first.')
      return context
    }

    // List integrations and choose one to edit
    const answer = (
      await this.interactor.chooseOption(
        [...ConcreteIntegrations, keywords.exit],
        'Select an integration to edit',
        `Project-wide integration settings will affect all users in this project.`
      )
    ).toUpperCase()

    if (!answer || answer === keywords.exit.toUpperCase()) {
      return context
    }

    // Get current project integration values
    const currentProjectIntegration = project.config.integration?.[answer]

    // Prompt for new values
    const apiUrl = await this.interactor.promptText(
      `API URL (Current: ${currentProjectIntegration?.apiUrl || 'Not set'})`,
      currentProjectIntegration?.apiUrl
    )

    const username = await this.interactor.promptText(
      `Username (Current: ${currentProjectIntegration?.username || 'Not set'})`,
      currentProjectIntegration?.username
    )

    const apiKey = await this.interactor.promptText(
      `API Key (Current: ${currentProjectIntegration?.apiKey ? 'Set' : 'Not set'})`,
      currentProjectIntegration?.apiKey
    )

    // Construct integration config
    const integrationConfig: IntegrationConfig = {}

    // Add non-empty values
    if (apiUrl) integrationConfig.apiUrl = apiUrl
    if (username) integrationConfig.username = username
    if (apiKey) integrationConfig.apiKey = apiKey

    // Save project-wide integration
    if (Object.keys(integrationConfig).length > 0) {
      this.services.project.save({
        integration: {
          ...project.config.integration,
          [answer]: integrationConfig,
        },
      })
      this.interactor.displayText(`Project-wide integration for ${answer} updated successfully.`)
    } else {
      this.interactor.displayText(`No changes made to ${answer} integration.`)
    }

    return context
  }
}
