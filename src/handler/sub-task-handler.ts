import {CommandHandler} from "./command-handler"
import {CommandContext} from "../model/command-context"
import {Interactor} from "../model/interactor"
import {IntegrationName} from "../model/integration-name"

export class SubTaskHandler extends CommandHandler {
  
  private help = `Define sub-task behavior by providing either:
      - "true" to permanently enable it
      - "false" or "0" to disable it
      - a number to limit calls.`
  
  constructor(private readonly interactor: Interactor) {
    super({
      commandWord: "sub-task",
      description: "Set the sub-task behavior",
      requiredIntegrations: [IntegrationName.OPENAI],
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)?.toLowerCase()
    
    if (!subCommand) {
      this.interactor.displayText(this.help)
      return context
    }
    
    let subTaskValue: number
    if (subCommand === "true") {
      subTaskValue = -1
      this.interactor.warn("Sub-tasking permanently enabled, this could allow nested sub-tasking and is not recommended. To disable use 'subtask false' or 'subtask [number]' to limit usage.")
    } else if (subCommand === "false") {
      subTaskValue = 0
      this.interactor.displayText("Sub-tasking disabled.")
    } else {
      subTaskValue = parseInt(subCommand, 10)
      if (isNaN(subTaskValue)) {
        this.interactor.error(`Invalid subTask value. Usage: ${this.help}`)
        return context
      }
      this.interactor.displayText(`Sub-tasking enabled for ${subTaskValue} calls of the function.`)
    }
    
    context.setSubTask(subTaskValue)
    return context
  }
}
