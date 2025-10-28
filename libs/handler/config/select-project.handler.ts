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

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const projectName = this.getSubCommand(_command)
      return (await this.selectProject(projectName)) ?? context
    } catch (_: any) {
      this.interactor.error(`Invalid project selection because: ${_.toString()}`)
      return context
    }
  }

  async selectProject(projectName: string): Promise<CommandContext | null> {
    this.services.project.selectProject(projectName)
    if (!this.services.project.selectedProject) return null
    return buildFirstCommandContext(this.interactor, this.services)
  }
}
