import { CommandHandler } from '../../model/command.handler'
import { Interactor } from '../../model/interactor'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { CommandContext } from '../../model/command-context'
import { AgentService } from '../../agent/agent.service'
import { generateThreadName } from '../generate-thread-name'

/**
 * Handler for saving the current AI thread state.
 * This triggers saving the thread and any post-processing like summarization.
 *
 * Usage:
 * - `save`: saves the current thread with its current name
 * - `save new thread name`: saves the current thread under a new name
 */
export class SaveAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService,
    private readonly agentService: AgentService
  ) {
    super({
      commandWord: 'save',
      description: 'Save current thread state, optionally under a new name.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const currentThread = this.threadService.getCurrentThread()
      if (!currentThread) {
        this.interactor.displayText('No active thread to save.')
        return context
      }

      // Get new name if provided using parent's getSubCommand
      const newName = this.getSubCommand(command)

      if (newName) {
        // Validate name length when trimmed
        if (newName.length < 5) {
          this.interactor.displayText('Please provide a more significant name (at least 5 characters).')
          return context
        }

        await this.threadService.save(newName)
        this.interactor.displayText(`Thread saved with new name: ${newName} (${currentThread.id})`)
      } else {
        // No name provided - check if thread needs a better name
        const needsBetterName =
          !currentThread.name || currentThread.name === 'Temporary thread' || currentThread.name === 'untitled'

        if (needsBetterName && currentThread.getUserMessageCount() > 0) {
          // Generate AI name like in auto-save
          try {
            const agent = await this.agentService.findByName('coday', context)
            let threadName: string
            if (!agent) {
              threadName = await this.interactor.promptText(
                `Default agent 'Coday' not available, thread name generation not available, please type the thread title`
              )
            } else {
              threadName = await generateThreadName(currentThread, agent)
            }
            await this.threadService.save(threadName)
            this.interactor.displayText(`Thread saved with generated name: ${threadName} (${currentThread.id})`)
          } catch (error) {
            // Fallback to manual save if AI generation fails
            await this.threadService.save()
            this.interactor.displayText(`Thread saved: ${currentThread.name} (${currentThread.id})`)
          }
        } else {
          await this.threadService.save()
          this.interactor.displayText(`Thread saved: ${currentThread.name} (${currentThread.id})`)
        }
      }
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
