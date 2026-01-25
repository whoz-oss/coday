import { runBash } from '@coday/function/run-bash'
import { CommandHandler } from './command-handler'
import { Interactor } from '@coday/model/interactor'
import { CommandContext } from './command-context'

export class RunBashHandler extends CommandHandler {
  constructor(private readonly interactor: Interactor) {
    super({
      commandWord: 'run-bash',
      description: 'Executes bash commands',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const bashCommand = this.getSubCommand(command)

    if (!bashCommand) {
      this.interactor.error('No bash command provided.')
      return context
    }

    try {
      const result = await runBash({
        command: bashCommand,
        root: context.project.root,
        interactor: this.interactor,
        requireConfirmation: false,
      })
      this.interactor.displayText(result)
    } catch (error) {
      this.interactor.error(`Error executing bash command: ${error}`)
    }

    return context
  }
}
