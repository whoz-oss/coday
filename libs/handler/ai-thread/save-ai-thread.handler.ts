import { CommandHandler } from '../../model/command.handler'
import { Interactor } from '../../model/interactor'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { CommandContext } from '../../model/command-context'
import { MessageEvent } from '@coday/coday-events'
import { AgentService } from '../../agent/agent.service'

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
        const needsBetterName = !currentThread.name || 
                               currentThread.name === 'Temporary thread' || 
                               currentThread.name === 'untitled'
        
        if (needsBetterName && currentThread.getUserMessageCount() > 0) {
          // Generate AI name like in auto-save
          try {
            const generatedName = await this.generateThreadName(currentThread, context)
            await this.threadService.save(generatedName)
            this.interactor.displayText(`Thread saved with generated name: ${generatedName} (${currentThread.id})`)
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

  /**
   * Generate a thread name using AI based on conversation content
   */
  private async generateThreadName(thread: any, context: CommandContext): Promise<string> {
    // Extract context from first few user messages
    const messages = thread.getMessages()
      .filter((msg: any) => msg instanceof MessageEvent && msg.role === 'user')
      .slice(0, 3)
      .map((msg: any) => msg.content)
      .join('\n\n')
    
    const prompt = `Generate a descriptive title for this conversation in one sentence:\n\n${messages}\n\nTitle:`
    
    // Get the default agent to use its AI client
    const agent = await this.agentService.findByName('coday', context)
    if (!agent) {
      throw new Error('Default agent not available')
    }
    
    const title = await agent.getAiClient().complete(prompt, {
      maxTokens: 50,
      temperature: 0.7,
      stopSequences: ['\n', '.']
    })
    
    // Clean up the title
    return title.trim().replace(/^["']|["']$/g, '').replace(/\.$/, '')
  }
}
