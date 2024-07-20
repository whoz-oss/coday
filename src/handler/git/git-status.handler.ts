import {CommandHandler} from "../../model/command.handler"
import {Interactor} from "../../model/interactor"
import {CommandContext} from "../../model/command-context"
import {runBash} from "../../function/run-bash"


export class GitStatusHandler extends CommandHandler {
  
  constructor(private interactor: Interactor) {
    super({
      commandWord: "status",
      description: "gives a summary of files status regarding git"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const result = await runBash({
      command: "git status",
      root: context.project.root,
      interactor: this.interactor,
    })
    this.interactor.displayText(result)
    return context
  }
}
