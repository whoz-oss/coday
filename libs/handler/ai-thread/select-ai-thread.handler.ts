import { CommandContext, CommandHandler, Interactor } from '../../model'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { selectAiThread } from './select-ai-thread'

/**
 * Handler for selecting an AI thread for use.
 * If no thread ID is provided, shows a selection interface.
 */
export class SelectAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: 'select',
      description: 'Select a thread interactively or by ID',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const threadId = this.getSubCommand(command)
      if (threadId) {
        await this.threadService.select(threadId)
        return context
      }

      await selectAiThread(this.interactor, this.threadService)

      return context
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
