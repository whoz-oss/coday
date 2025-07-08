import { CommandContext, CommandHandler, ConcreteIntegrations, IntegrationConfig } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { keywords } from '../../keywords'

const MASKED_VALUE = '********'

export class UserIntegrationHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'user',
      description: 'Edit personal integration, overrides for current project',
    })
  }

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
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
        `User-level integrations override project configurations.\nSelect an integration to customize:`
      )
    ).toUpperCase()

    if (!answer || answer === keywords.exit.toUpperCase()) {
      return context
    }

    // Get current project and user integration values
    const currentProjectIntegration = project.config.integration?.[answer]
    const currentUserIntegration = this.services.user.config.projects?.[project.name]?.integration?.[answer]

    // Prompt for new values with project-level context
    const apiUrl = await this.interactor.promptText(
      `API URL (Project default: ${currentProjectIntegration?.apiUrl || 'Not set'})`,
      currentUserIntegration?.apiUrl
    )

    const username = await this.interactor.promptText(
      `Username (Project default: ${currentProjectIntegration?.username || 'Not set'})`,
      currentUserIntegration?.username
    )

    // Special handling for API key
    const currentApiKey = currentUserIntegration?.apiKey
    const apiKeyPrompt = currentApiKey
      ? `API Key (Current value is masked, Project default: ${currentProjectIntegration?.apiKey ? 'Set' : 'Not set'})`
      : `API Key (Project default: ${currentProjectIntegration?.apiKey ? 'Set' : 'Not set'})`

    const apiKeyInput = await this.interactor.promptText(apiKeyPrompt, currentApiKey ? MASKED_VALUE : undefined)

    // Construct integration config
    const integrationConfig: IntegrationConfig = {}

    // API URL
    if (apiUrl) integrationConfig.apiUrl = apiUrl

    // Username
    if (username) integrationConfig.username = username

    // API Key handling
    if (apiKeyInput === MASKED_VALUE) {
      // Keep existing API key
      if (currentApiKey) integrationConfig.apiKey = currentApiKey
    } else if (apiKeyInput === '' || apiKeyInput === null) {
      // Explicitly remove API key
      // Do not add to config, which will effectively remove it
    } else {
      // New API key provided
      integrationConfig.apiKey = apiKeyInput
    }

    // Save user-level integration
    if (Object.keys(integrationConfig).length > 0) {
      this.services.user.setProjectIntegration(project.name, { [answer]: integrationConfig })
      this.interactor.displayText(`User-level integration for ${answer} updated successfully.`)
    } else {
      this.interactor.displayText(`No changes made to ${answer} integration.`)
    }

    return context
  }
}
