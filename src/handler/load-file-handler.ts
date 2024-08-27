import {CommandContext, CommandHandler, Interactor} from "../model"
import {OpenaiClient} from "./openai-client"
import {readFileByPath} from "../integration/file/read-file-by-path"

export class LoadFileHandler extends CommandHandler {
  private openaiClient: OpenaiClient
  
  constructor(private interactor: Interactor, openaiClient: OpenaiClient) {
    super({
      commandWord: "load-file",
      description: "Loads a file and adds its content to the LLM context.",
    })
    this.openaiClient = openaiClient
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    const filePath = subCommand.trim()
    
    if (!filePath) {
      this.interactor.error("Please provide a valid file path.")
      return context
    }
    
    try {
      const content = readFileByPath({
        relPath: filePath,
        root: context.project.root,
        interactor: this.interactor
      })
      await this.openaiClient.addMessage(content, context)
      this.interactor.displayText(`File loaded: ${filePath}`)
    } catch (error: any) {
      this.interactor.error(`Failed to load file: ${error}`)
    }
    
    return context
  }
}
