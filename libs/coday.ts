import { AiThread } from './ai-thread/ai-thread'
import { AiThreadService } from './ai-thread/ai-thread.service'
import { RunStatus } from './ai-thread/ai-thread.types'
import { AiThreadRepositoryFactory } from './ai-thread/repository/ai-thread.repository.factory'
import { AiHandler, ConfigHandler } from './handler'
import { HandlerLooper } from './handler-looper'
import { selectAiThread } from './handler/ai-thread/select-ai-thread'
import { AiClientProvider } from './integration/ai/ai-client-provider'
import { keywords } from './keywords'
import { CommandContext, Interactor } from './model'
import { AnswerEvent, MessageEvent, TextEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { AgentService } from './agent'
import { CodayOptions } from './options'
import { CodayServices } from './coday-services'

const MAX_ITERATIONS = 100

export class Coday {
  context: CommandContext | null = null
  configHandler: ConfigHandler

  handlerLooper: HandlerLooper | undefined
  aiHandler: AiHandler | undefined
  maxIterations: number
  initialPrompts: string[] = []

  private killed: boolean = false

  private aiThreadService: AiThreadService
  private aiClientProvider: AiClientProvider

  constructor(
    private interactor: Interactor,
    private options: CodayOptions,
    private services: CodayServices
  ) {
    this.interactor.debugLevelEnabled = options.debug
    this.interactor.debug('Coday started with debug')
    this.configHandler = new ConfigHandler(interactor, this.services)
    this.maxIterations = MAX_ITERATIONS
    this.aiThreadService = new AiThreadService(new AiThreadRepositoryFactory(this.services.project), services.user)
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
   * Replay messages from an AiThread through the interactor
   */
  private replayThread(aiThread: AiThread): void {
    const messages = aiThread.getMessages()
    if (!messages?.length) return
    // Sort messages by timestamp to maintain chronological order
    const sortedMessages = [...messages].sort(
      (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
    )

    // Convert and emit each message
    for (const message of sortedMessages) {
      if (message instanceof MessageEvent) {
        if (message.role === 'assistant') {
          this.interactor.sendEvent(new TextEvent({ ...message, speaker: message.name, text: message.content }))
        } else {
          this.interactor.sendEvent(new AnswerEvent({ ...message, answer: message.content, invite: message.name }))
        }
      } else if (message instanceof ToolRequestEvent || message instanceof ToolResponseEvent) {
        this.interactor.sendEvent(message)
      }
    }

    // Always emit the thread selection message last
    this.interactor.displayText(`Selected thread '${aiThread.name}'`)
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

  async run(): Promise<void> {
    this.initialPrompts = this.options.prompts ? [...this.options.prompts] : []
    // Main loop to keep waiting for user input
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

      const thread = this.context.aiThread
      if (thread) thread.runStatus = RunStatus.RUNNING

      // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
      this.context.addCommands(userCommand!)

      try {
        this.context = await this.handlerLooper!.handle(this.context)
      } finally {
        this.stop()
      }
    } while (!this.context?.oneshot)
  }

  /**
   * Stops the current AI processing gracefully:
   * - Preserves thread and context state
   * - Allows clean completion of current operation
   * - Prevents new processing steps
   *
   * @returns Promise that resolves when stop is complete
   */
  stop(): void {
    const thread = this.context?.aiThread
    if (thread) thread.runStatus = RunStatus.STOPPED
    this.handlerLooper?.stop()
    this.aiThreadService.autoSave()
  }

  /**
   * Immediately terminates all processing.
   * Unlike stop(), this method:
   * - Does not preserve state
   * - Immediately ends all processing
   * - May leave cleanup needed
   */
  kill(): void {
    this.stop()
    this.handlerLooper?.kill()
    this.interactor.kill()
  }

  private async initContext(): Promise<void> {
    if (this.context) {
      return
    }

    this.context = await this.configHandler.selectProjectHandler.selectProject(this.options.project)

    if (this.context) {
      this.context.oneshot = this.options.oneshot
      this.context.fileReadOnly = this.options.fileReadOnly
      // Create and store the agent service
      this.services.agent = new AgentService(
        this.interactor,
        this.aiClientProvider,
        this.services,
        this.context.project.root,
        this.options.agentFolders
      )
      this.aiHandler = new AiHandler(this.interactor, this.services.agent)
      this.handlerLooper = new HandlerLooper(
        this.interactor,
        this.aiHandler,
        this.aiThreadService,
        this.configHandler,
        this.services
      )
      this.handlerLooper.init(this.context.project)
      await this.services.agent.initialize(this.context)
    }
  }

  private async initThread(): Promise<void> {
    if (!this.context?.aiThread) {
      await selectAiThread(this.interactor, this.aiThreadService)
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
      const projectName = this.context?.project.name
      userCommand = await this.interactor.promptText(`${this.services.user.username} (${projectName})`)
    }
    return userCommand
  }
}
