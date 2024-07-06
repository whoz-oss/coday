import os from "os"
import {CommandContext} from "./model/command-context"
import {Interactor} from "./model/interactor"
import {ConfigHandler, OpenaiHandler,} from "./handler"
import {HandlerLooper} from "./handler-looper"
import {keywords} from "./keywords"

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
    let prompts = this.options.prompts ? [...this.options.prompts] : []
    // Main loop to keep waiting for user input
    do {
      // initiate context in loop for when context is cleared
      if (!this.context) {
        this.context = await this.configHandler.initContext(
          this.options.project,
        )
        if (this.options.oneshot && !this.context) {
          this.interactor.error("Could not initialize context")
          break
        }
        this.context!.oneshot = this.options.oneshot
        this.handlerLooper.init(this.userInfo.username)
        continue
      }
      
      let userCommand: string
      if (prompts.length) {
        // if initial prompt(s), set the first as userCommand and add the others to the queue
        userCommand = prompts.shift()!
        if (prompts.length) {
          this.context.addCommands(...prompts)
          prompts = [] // clear the prompts
        }
      } else if (!this.options.oneshot) {
        // allow user input
        userCommand = await this.interactor.promptText(
          `${this.userInfo.username}`,
        )
      } else {
        // default case: no initial prompt and not interactive = get out
        break
      }
      
      // quit loop if user wants to exit
      if (userCommand === keywords.exit) {
        break
      }
      // reset context and project selection
      if (userCommand === keywords.reset) {
        this.context = null
        this.openaiHandler.reset()
        this.configHandler.resetProjectSelection()
        continue
      }
      
      // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
      this.context.addCommands(userCommand)
      
      this.context = await this.handlerLooper.handle(this.context)
    } while (!(this.context?.oneshot))
  }
}
