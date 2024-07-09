import {CommandHandler} from "../command.handler"
import {CommandContext} from "../../model/command-context"
import {Interactor} from "../../model/interactor"
import {configService} from "../../service/config.service"
import {addProject} from "./add-project"
import {selectProject} from "./select-project"

export class SelectProjectHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private username: string,
  ) {
    super({
      commandWord: "select-project",
      description: "Select an existing project"
    })
  }
  
  async handle(
    command: string,
    context: CommandContext,
  ): Promise<CommandContext> {
    const names = configService.projectNames
    const selection = await this.interactor.chooseOption(
      [...names, "new"],
      "Selection: ",
      "Choose an existing project or select \"new\" to create one",
    )
    if (selection === "new") {
      const newContext = await addProject(this.interactor, this.username)
      if (newContext) {
        return newContext
      }
      return context
    }
    try {
      const newContext = await selectProject(selection, this.interactor, this.username)
      if (newContext) {
        return newContext
      }
      return context
    } catch (_) {
      this.interactor.error("Invalid project selection")
      return context
    }
  }
}
