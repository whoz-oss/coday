import {CommandHandler} from "./command.handler"
import {Interactor} from "../model/interactor"
import {runBash} from "../function/run-bash"
import {CommandContext} from "../model/command-context"

export class DebugHandler extends CommandHandler {
  
  constructor(private interactor: Interactor) {
    super({
      commandWord: "debug",
      description: "run a command for dev-testing purposes",
      isInternal: true,
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const method = ({command, relPath}: { command: string, relPath: string }) => {
      return runBash({command, root: context.project.root, interactor: this.interactor})
    }
    const args = {command: "ls", relPath: "/src/function"}
    console.log("direct read")
    const result = await method(args)
    
    console.log(result)
    return context
  }
  
}