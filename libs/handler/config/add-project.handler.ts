import { CommandContext, CommandHandler, Interactor } from '../../model'
import { addProject } from './add-project'

export class AddProjectHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private username: string
  ) {
    super({
      commandWord: 'add-project',
      description: 'Add a new project',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const newContext = await addProject(this.interactor, this.username)
    if (newContext) {
      return newContext
    }
    return context
  }
}
