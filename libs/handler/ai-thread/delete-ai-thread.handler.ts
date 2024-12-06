import { CommandHandler } from '../../model/command.handler'
import { Interactor } from '../../model/interactor'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { CommandContext } from '../../model/command-context'
import { lastValueFrom } from 'rxjs'

/**
 * Handler for deleting an AI thread.
 */
export class DeleteAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete a thread (interactive selection)',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      // Get threads and prepare selection
      const threads = await lastValueFrom(this.threadService.list())
      if (threads.length === 0) {
        this.interactor.displayText('No threads available to delete.')
        return context
      }

      const currentThread = this.threadService.getCurrentThread()
      const threadsByText = new Map<string, string>()

      threads.forEach((thread) => {
        const text = `${thread.id}: ${currentThread?.id === thread.id ? '[CURRENT] ' : ''}${thread.name}`
        threadsByText.set(text, thread.id)
      })

      const options = Array.from(threadsByText.keys())
      const selected = await this.interactor.chooseOption(
        options,
        'Select a thread to delete',
        'Warning: This action cannot be undone'
      )

      const selectedId = threadsByText.get(selected)
      if (!selectedId) {
        this.interactor.error('Failed to get selected thread ID')
        return context
      }

      // Delete and handle re-selection if needed
      await this.threadService.delete(selectedId)
      this.interactor.displayText(`Thread deleted: ${selectedId}`)

      // Note: selection of a new thread if current was deleted is handled by the service
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
