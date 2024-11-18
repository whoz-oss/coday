import os from "os"
import {HandlerLooper} from "./handler-looper"
import {keywords} from "./keywords"
import {AiClient, CommandContext, Interactor} from "./model"
import {AiHandler, ConfigHandler} from "./handler"
import {AiClientProvider} from "./integration/ai/ai-client-provider"
import {AiThreadService} from "./ai-thread/ai-thread.service"
import {AiThreadRepositoryFactory} from "./ai-thread/repository/ai-thread.repository.factory"
import {configService} from "./service/config.service"
import {RunStatus} from "./ai-thread/ai-thread.types"

const MAX_ITERATIONS = 100

interface CodayOptions {
  oneshot: boolean
  project?: string
  prompts?: string[]
}

export class Coday {
  userInfo: os.UserInfo<string>
  context: CommandContext | null = null
  configHandler: ConfigHandler
  
  handlerLooper: HandlerLooper | undefined
  aiHandler: AiHandler | undefined
  aiClient: AiClient | undefined
  maxIterations: number
  initialPrompts: string[] = []
  
  private killed: boolean = false
  
  private aiThreadService: AiThreadService
  
  constructor(
    private interactor: Interactor,
    private options: CodayOptions,
  ) {
    this.userInfo = os.userInfo()
    this.configHandler = new ConfigHandler(interactor, this.userInfo.username)
    this.maxIterations = MAX_ITERATIONS
    this.aiThreadService = new AiThreadService(new AiThreadRepositoryFactory(configService))
    this.aiThreadService.activeThread.subscribe(aiThread => {
      if (!this.context || !aiThread) return
      this.context.aiThread = aiThread
    })
  }
  
  async run(): Promise<void> {
    this.initialPrompts = this.options.prompts ? [...this.options.prompts] : []
    // Main loop to keep waiting for user input
    do {
      if (this.killed) {
        return
      }
      await this.initContext()
      if (!this.context) {
        this.interactor.error("Could not initialize context 😭")
        break
      }
      
      let userCommand = await this.initCommand()
      if ((!userCommand && this.options.oneshot) || userCommand === keywords.exit) {
        // default case: no initial prompt (or empty) and not interactive = get out
        break
      }
      
      if (userCommand === keywords.reset) {
        this.context = null
        this.aiHandler?.reset()
        this.configHandler.resetProjectSelection()
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
    } while (!(this.context?.oneshot))
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
    if (!this.context) {
      this.context = await this.configHandler.initContext(
        this.options.project,
      )
      if (this.context) {
        this.context.aiThread = await this.aiThreadService.select()
        this.context.oneshot = this.options.oneshot
        this.aiClient = new AiClientProvider(this.interactor).getClient()
        this.aiHandler = new AiHandler(this.interactor, this.aiClient)
        this.handlerLooper = new HandlerLooper(this.interactor, this.aiHandler, this.aiClient, this.aiThreadService)
        this.handlerLooper.init(this.userInfo.username, this.context.project)
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
      const projectName = this.context?.project.name
      userCommand = await this.interactor.promptText(
        `${this.userInfo.username} (${projectName})`,
      )
    }
    return userCommand
  }
}
