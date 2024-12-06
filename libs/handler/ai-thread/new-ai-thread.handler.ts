import { CommandContext, CommandHandler, Interactor } from '../../model'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { AiThread } from '../../ai-thread/ai-thread'

export class NewAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: 'new',
      description: 'Creates and select a new thread, with an optional name.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const name = this.getSubCommand(command)

    const created: AiThread = this.threadService.create(name)
    this.interactor.displayText(`Created and selected new thread '${created.name}', with id '${created.id}'`)

    return context
  }
}
