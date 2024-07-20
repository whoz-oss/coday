import os from "os"
import {CommandContext} from "./model/command-context"
import {Interactor} from "./model/interactor"
import {HandlerLooper} from "./handler-looper"
import {keywords} from "./keywords"
import {OpenaiHandler} from "./handler/openai/openai.handler"
import {ConfigHandler} from "./handler/config/config.handler"

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
  
  handlerLooper: HandlerLooper
  openaiHandler: OpenaiHandler
  maxIterations: number
  initialPrompts: string[] = []
  
  constructor(
    private interactor: Interactor,
    private options: CodayOptions,
  ) {
    this.userInfo = os.userInfo()
    this.configHandler = new ConfigHandler(interactor, this.userInfo.username)
    this.openaiHandler = new OpenaiHandler(interactor)
    this.handlerLooper = new HandlerLooper(interactor, this.openaiHandler)
    this.maxIterations = MAX_ITERATIONS
  }
  
  async run(): Promise<void> {
    this.initialPrompts = this.options.prompts ? [...this.options.prompts] : []
    // Main loop to keep waiting for user input
    do {
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
        this.openaiHandler.reset()
        this.configHandler.resetProjectSelection()
        continue
      }
      
      // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
      this.context.addCommands(userCommand!)
      
      this.context = await this.handlerLooper.handle(this.context)
    } while (!(this.context?.oneshot))
  }
  
  private async initContext(): Promise<void> {
    if (!this.context) {
      this.context = await this.configHandler.initContext(
        this.options.project,
      )
      if (this.context) {
        this.context.oneshot = this.options.oneshot
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
