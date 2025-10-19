import { AiThread } from './ai-thread/ai-thread'
import { AiThreadService } from './ai-thread/ai-thread.service'
import { RunStatus, ThreadMessage } from './ai-thread/ai-thread.types'
import { AiThreadRepositoryFactory } from './ai-thread/repository/ai-thread.repository.factory'
import { AiHandler, ConfigHandler } from './handler'
import { HandlerLooper } from './handler-looper'
import { AiClientProvider } from './integration/ai/ai-client-provider'
import { keywords } from './keywords'
import { CommandContext, Interactor } from './model'
import {
  InviteEventDefault,
  MessageContent,
  MessageEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@coday/coday-events'
import { AgentService } from './agent'
import { CodayOptions } from './options'
import { CodayServices } from './coday-services'
import { AiConfigService } from './service/ai-config.service'

const MAX_ITERATIONS = 100

export class Coday {
  context: CommandContext | null = null
  configHandler: ConfigHandler

  handlerLooper: HandlerLooper | undefined
  aiHandler: AiHandler | undefined
  maxIterations: number
  initialPrompts: string[] = []
  aiThreadService: AiThreadService
  private killed: boolean = false
  private aiClientProvider: AiClientProvider

  constructor(
    public readonly interactor: Interactor,
    private options: CodayOptions,
    private services: CodayServices
  ) {
    this.interactor.debugLevelEnabled = options.debug
    this.interactor.debug('Coday started with debug')
    this.configHandler = new ConfigHandler(interactor, this.services)
    this.maxIterations = MAX_ITERATIONS
    this.aiThreadService = new AiThreadService(
      new AiThreadRepositoryFactory(this.services.project),
      services.user,
      interactor
    )
    this.aiThreadService.activeThread.subscribe((aiThread) => {
      if (!this.context || !aiThread) return
      this.context.aiThread = aiThread
      this.replayThread(aiThread)
    })
    this.aiClientProvider = new AiClientProvider(
      this.interactor,
      this.services.user,
      this.services.project,
      this.services.logger
    )
  }

  /**
   * Replay the current thread's messages.
   * Useful for reconnection scenarios.
   */
  replay(): void {
    const thread = this.aiThreadService.getCurrentThread()
    if (!thread) {
      console.log('No active thread to replay')
      return
    }
    this.replayThread(thread)

    // After replay, if not oneshot and thread is not running, re-emit last invite
    if (!this.options.oneshot && thread.runStatus !== RunStatus.RUNNING) {
      this.interactor.replayLastInvite()
    }
  }

  /**
   * Upload content to the current AI thread as a user message.
   * @param content Array of MessageContent to upload
   */
  upload(content: MessageContent[]): void {
    const thread = this.aiThreadService.getCurrentThread()
    if (!thread) {
      this.interactor.error('No active thread available for upload')
      return
    }

    const username = this.services.user.username

    // Add each content item as a user message
    content.forEach((item) => {
      thread.addUserMessage(username, item)
    })

    // Send the message event to update the UI
    const messageEvent = new MessageEvent({
      role: 'user',
      content,
      name: username,
    })
    this.interactor.sendEvent(messageEvent)
  }

  /**
   * Get the services instance (for internal use)
   * @returns CodayServices instance
   */
  getServices(): CodayServices {
    return this.services
  }

  async run(): Promise<void> {
    this.initialPrompts = this.options.prompts ? [...this.options.prompts] : []
    // Main loop to keep waiting for user input
    try {
      do {
        if (this.killed) {
          return
        }
        await this.initContext()
        await this.initThread()
        if (!this.context) {
          this.interactor.error('Could not initialize context 😭')
          break
        }

        let userCommand = await this.initCommand()
        if ((!userCommand && this.options.oneshot) || userCommand === keywords.exit) {
          // default case: no initial prompt (or empty) and not interactive = get out
          break
        }

        if (userCommand === keywords.reset) {
          this.context = null
          this.services.project.resetProjectSelection()
          continue
        }

        const thread = this.context?.aiThread
        if (thread) {
          thread.runStatus = RunStatus.RUNNING
        }

        // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
        this.context?.addCommands(userCommand!)

        this.context = (await this.handlerLooper?.handle(this.context)) ?? null
      } while (!this.context?.oneshot)
    } finally {
      this.stop()
    }

    // Always cleanup resources when conversation ends normally
    // This ensures MCP Docker containers are stopped
    await this.cleanup()
  }

  /**
   * Stops the current AI processing gracefully:
   * - Preserves thread and context state
   * - Allows clean completion of current operation
   * - Prevents new processing steps
   * - Keeps Coday instance alive for potential resume
   */
  stop(): void {
    const thread = this.context?.aiThread
    if (thread) thread.runStatus = RunStatus.STOPPED
    this.handlerLooper?.stop()
    this.aiThreadService.autoSave()
  }

  /**
   * Cleanup resources at the end of a conversation.
   * - Stops MCP servers and Docker containers tied to agents
   * - Preserves thread state and Coday instance
   * - Keeps instance ready for new conversations
   * - Called when conversation ends normally (exit, oneshot completion)
   */
  async cleanup(): Promise<void> {
    try {
      if (this.services.agent) {
        await this.services.agent.kill()
      }
      this.aiThreadService.kill()

      // Reset AI client provider for fresh connections
      this.aiClientProvider.cleanup()

      // Clear context but keep services available
      this.context = null
      this.handlerLooper = undefined
      this.aiHandler = undefined
    } catch (error) {
      console.error('Error during agent cleanup:', error)
      // Don't throw - cleanup should be best-effort
    }
  }

  /**
   * Force terminate everything and destroy the instance.
   * - Stops all processing immediately
   * - Cleans up all resources
   * - Destroys the Coday instance
   * - Used for forced termination (Ctrl+C, process exit)
   */
  async kill(): Promise<void> {
    this.killed = true

    // Stop processing and autosave BEFORE cleanup
    // This ensures thread service is still active when autosave is called
    this.stop()

    try {
      // Now cleanup resources after autosave is complete
      await this.cleanup()
    } catch (error) {
      console.error('Error during kill cleanup:', error)
    }

    this.handlerLooper?.kill()
    this.interactor.kill()
  }

  /**
   * Replay messages from an AiThread through the interactor
   */
  private async replayThread(aiThread: AiThread): Promise<void> {
    const messages: ThreadMessage[] = (await aiThread.getMessages(undefined, undefined)).messages
    if (!messages?.length) return
    // Sort messages by timestamp to maintain chronological order
    const sortedMessages = [...messages].sort(
      (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
    )

    // Send messages directly - no conversion needed
    for (const message of sortedMessages) {
      if (message instanceof MessageEvent) {
        // Send MessageEvent directly - frontend now handles rich content
        this.interactor.sendEvent(message)
      } else if (message instanceof ToolRequestEvent || message instanceof ToolResponseEvent) {
        this.interactor.sendEvent(message)
      }
    }

    // Always emit the thread selection message last
    this.interactor.displayText(`Selected thread '${aiThread.name}'`)
  }

  private async initContext(): Promise<void> {
    if (this.context) {
      return
    }

    this.context = await this.configHandler.selectProjectHandler.selectProject(this.options.project)

    if (this.context) {
      this.context.oneshot = this.options.oneshot
      this.context.fileReadOnly = this.options.fileReadOnly

      // Create and store the aiConfig service (late init)
      this.services.aiConfig = new AiConfigService(this.services.user, this.services.project)
      this.services.aiConfig.initialize(this.context)

      // Initialize the MCP service with context
      this.services.mcp.initialize(this.context)

      // Create and store the agent service
      this.services.agent = new AgentService(
        this.interactor,
        this.aiClientProvider,
        this.services,
        this.context.project.root,
        this.options.agentFolders
      )
      this.aiHandler = new AiHandler(this.interactor, this.services.agent, this.aiThreadService)
      this.handlerLooper = new HandlerLooper(
        this.interactor,
        this.aiHandler,
        this.aiThreadService,
        this.configHandler,
        this.services
      )
      this.aiClientProvider.init(this.context)
      this.handlerLooper.init(this.context.project)
      await this.services.agent.initialize(this.context)
    }
  }

  private async initThread(): Promise<void> {
    if (!this.context?.aiThread) {
      // If threadId provided in options, select that specific thread
      if (this.options.thread) {
        await this.aiThreadService.select(this.options.thread)
      } else {
        // Default behavior: select most recent or create new
        await this.aiThreadService.select()
      }
    }
  }

  private async initCommand(): Promise<string | undefined> {
    let userCommand: string | undefined
    if (this.initialPrompts.length) {
      // if initial prompt(s), set the first as userCommand and add the others to the queue
      userCommand = this.initialPrompts.shift()!
      if (this.initialPrompts.length) {
        this.context?.addCommands(...this.initialPrompts)
        this.initialPrompts = [] // clear the prompts as consumed, will not be re-used even on context reset
      }
    } else if (!this.options.oneshot) {
      // allow user input
      userCommand = await this.interactor.promptText(InviteEventDefault)
    }
    return userCommand
  }
}
