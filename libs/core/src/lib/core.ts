import * as path from 'path'
import { CommandContext } from '@coday/handler'
import { ConfigHandler } from '@coday/handlers-config'
import { HandlerLooper } from '@coday/handlers-looper'
import { AiHandler } from '@coday/handlers-openai'
import { AiThread, RunStatus, ThreadMessage, ThreadStateService } from '@coday/ai-thread'
import { Interactor } from '@coday/model'
import { CodayOptions } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { InviteEventDefault, MessageContent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/model'
import { AgentService } from '@coday/agent'
import { AiConfigService } from '@coday/service'
import { AiClientProvider } from '@coday/integrations-ai'

const MAX_ITERATIONS = 100

export class Coday {
  context: CommandContext | null = null
  configHandler: ConfigHandler

  handlerLooper: HandlerLooper | undefined
  aiHandler: AiHandler | undefined
  maxIterations: number
  initialPrompts: string[] = []
  aiThreadService: ThreadStateService
  private killed: boolean = false
  private readonly aiClientProvider: AiClientProvider

  constructor(
    public readonly interactor: Interactor,
    private readonly options: CodayOptions,
    public readonly services: CodayServices
  ) {
    this.interactor.debugLevelEnabled = options.debug
    this.interactor.debug('Coday started with debug')
    this.configHandler = new ConfigHandler(interactor, this.services)
    this.maxIterations = MAX_ITERATIONS
    this.aiThreadService = new ThreadStateService(
      services.user,
      services.thread.getThreadRepository(options.project!),
      options.project!,
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

  async run(): Promise<void> {
    this.initialPrompts = this.options.prompts ? [...this.options.prompts] : []
    this.interactor.debug(`[CODAY] Starting run with ${this.initialPrompts.length} initial prompts`)
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
        if (!userCommand && this.options.oneshot) {
          // default case: no initial prompt (or empty) and not interactive = get out
          break
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
      if (
        message instanceof MessageEvent ||
        message instanceof ToolRequestEvent ||
        message instanceof ToolResponseEvent
      ) {
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

    this.context = await this.configHandler.selectProjectHandler.selectProject(this.options.project!)

    if (this.context) {
      this.context.oneshot = this.options.oneshot
      this.context.fileReadOnly = this.options.fileReadOnly

      // Set thread files root if thread is selected
      if (this.options.thread && this.options.project) {
        // Thread files are stored in the config directory, not the project directory
        // Path: ~/.coday/projects/{projectName}/threads/{threadId}-files
        this.context.threadFilesRoot = path.join(
          this.options.configDir,
          'projects',
          this.options.project,
          'threads',
          `${this.options.thread}-files`
        )

        this.interactor.debug(`[CODAY] Thread files root: ${this.context.threadFilesRoot}`)
        // Note: Directory creation is handled lazily by ThreadFileService on first use
      }

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
      this.handlerLooper = new HandlerLooper(this.interactor, this.aiHandler, this.configHandler, this.services)
      this.aiClientProvider.init(this.context)
      this.handlerLooper.init(this.context.project)
      await this.services.agent.initialize(this.context)
    }
  }

  private async initThread(): Promise<void> {
    if (this.context?.aiThread) {
      return
    }
    if (!this.options.thread) {
      throw Error('No thread given, cannot start Coday instance')
    }
    await this.aiThreadService.select(this.options.thread)
  }

  private async initCommand(): Promise<string | undefined> {
    let userCommand: string | undefined
    this.interactor.debug(
      `[CODAY] initCommand: ${this.initialPrompts.length} prompts available, oneshot=${this.options.oneshot}`
    )
    if (this.initialPrompts.length) {
      // if initial prompt(s), set the first as userCommand and add the others to the queue
      userCommand = this.initialPrompts.shift()!
      this.interactor.debug(`[CODAY] Using initial prompt: ${userCommand}`)
      if (this.initialPrompts.length) {
        this.interactor.debug(`[CODAY] Adding ${this.initialPrompts.length} remaining prompts to queue`)
        this.context?.addCommands(...this.initialPrompts)
        this.initialPrompts = [] // clear the prompts as consumed, will not be re-used even on context reset
      }
    } else if (!this.options.oneshot) {
      // allow user input
      this.interactor.debug(`[CODAY] No initial prompts, waiting for user input`)
      userCommand = await this.interactor.promptText(InviteEventDefault)
    } else {
      this.interactor.debug(`[CODAY] No initial prompts and oneshot mode, exiting`)
    }
    return userCommand
  }
}
