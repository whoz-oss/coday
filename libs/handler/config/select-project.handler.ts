import { CommandContext } from '../../model/command-context'
import { Interactor } from '../../model/interactor'
import { buildFirstCommandContext } from './select-project'
import { CommandHandler } from '../../model/command.handler'
import { CodayServices } from '../../coday-services'

export class SelectProjectHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'select-project',
      description: 'Select an existing project',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      return (await this.selectProject()) ?? context
    } catch (_: any) {
      this.interactor.error(`Invalid project selection because: ${_.toString()}`)
      return context
    }
  }

  async selectProject(projectName?: string): Promise<CommandContext | null> {
    let selection = projectName
    if (!selection) {
      const names = this.services.project.projects
      selection = await this.interactor.chooseOption(
        [...names, 'new'],
        'Selection: ',
        'Choose an existing project or select "new" to create one'
      )
    }
    if (selection === 'new') {
      const projectName = await this.interactor.promptText('Project name')
      const projectPath = await this.interactor.promptText('Project path, no trailing slash')
      this.services.project.addProject(projectName, projectPath)
      if (!this.services.project.selectedProject) return null
      return buildFirstCommandContext(this.interactor, this.services)
    }
    this.services.project.selectProject(selection)
    if (!this.services.project.selectedProject) return null
    return buildFirstCommandContext(this.interactor, this.services)
  }
}
