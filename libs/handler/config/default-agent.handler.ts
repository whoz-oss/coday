import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'

export class DefaultAgentHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'default-agent',
      description: 'Set the default agent for the current project'
    })
  }

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    // Ensure a project is selected
    if (!this.services.project.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return context
    }

    // Make sure agent service is available
    if (!this.services.agent) {
      this.interactor.error('Agent service not available. Please try again after interacting with an agent.')
      return context
    }

    // Get the current project name
    const projectName = this.services.project.selectedProject.name

    // Initialize agents to get the list
    await this.services.agent.initialize(context)
    
    // Get the list of available agents
    const availableAgents = this.services.agent.listAgentSummaries()
    
    if (availableAgents.length === 0) {
      this.interactor.error('No agents available.')
      return context
    }

    // Add "None" option to clear the default agent setting
    const options = [
      { name: 'None (use Coday)', value: '' },
      ...availableAgents.map(agent => ({ 
        name: `${agent.name}${agent.name.toLowerCase() === 'coday' ? ' (default)' : ''}`, 
        value: agent.name 
      }))
    ]

    // Get the current default agent if any
    const userConfig = this.services.user.config
    const currentDefault = userConfig.projects?.[projectName]?.defaultAgent || ''
    
    // Mark the current default in the options list
    const displayOptions = options.map(opt => ({
      ...opt,
      name: opt.value === currentDefault ? `${opt.name} (current)` : opt.name
    }))

    try {
      // Let the user select an agent
      const selection = await this.interactor.chooseOption(
        displayOptions.map(o => o.name),
        'Select the default agent for this project:'
      )

      // Find the selected option
      const selectedOption = displayOptions.find(o => o.name === selection)
      const selectedAgentName = selectedOption?.value || ''

      // Update the user config
      if (!userConfig.projects) {
        userConfig.projects = {}
      }
      
      if (!userConfig.projects[projectName]) {
        userConfig.projects[projectName] = {
          integration: {}
        }
      }

      if (selectedAgentName) {
        userConfig.projects[projectName].defaultAgent = selectedAgentName
        this.interactor.displayText(`Default agent for project "${projectName}" set to "${selectedAgentName}".`)
      } else {
        // Remove the default agent setting
        delete userConfig.projects[projectName].defaultAgent
        this.interactor.displayText(`Default agent for project "${projectName}" cleared. Using Coday as default.`)
      }

      // Save the updated config
      this.services.user.save()
      
    } catch (error) {
      this.interactor.error('Selection cancelled or failed.')
    }

    return context
  }
}