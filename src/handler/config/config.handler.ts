import {Interactor} from "../../model/interactor"
import {AddProjectHandler} from "./add-project.handler"
import {SelectProjectHandler} from "./select-project.handler"
import {EditIntegrationHandler} from "./edit-integration.handler"
import {CommandContext} from "../../model/command-context"
import {configService} from "../../service/config.service"
import {addProject} from "./add-project"
import {selectProject} from "./select-project"
import {NestedHandler} from "../../model/nested.handler"

export class ConfigHandler extends NestedHandler {
  protected interactor: Interactor
  protected username: string
  
  constructor(
    interactor: Interactor,
    username: string,
  ) {
    super({
      commandWord: "config",
      description: "handles config related commands"
    }, interactor)
    
    this.interactor = interactor
    this.username = username
    
    this.handlers = [
      new AddProjectHandler(this.interactor, this.username),
      new SelectProjectHandler(this.interactor, this.username),
      new EditIntegrationHandler(this.interactor)
    ]
  }
  
  async initContext(
    initialProject: string | undefined,
  ): Promise<CommandContext | null> {
    if (initialProject) {
      console.log(`selecting ${initialProject}...`)
      return await selectProject(initialProject, this.interactor, this.username)
    }
    const projectNames = configService.projectNames
    if (!projectNames.length) {
      // no projects at all, force user define one
      this.interactor.displayText(
        "No existing project, please define one by its name",
      )
      return addProject(this.interactor, this.username)
    }
    if (projectNames.length === 1) {
      return await selectProject(projectNames[0], this.interactor, this.username)
    }
    
    return await this.chooseProject()
  }
  
  
  resetProjectSelection(): void {
    configService.resetProjectSelection()
  }
  
  private async chooseProject(): Promise<CommandContext | null> {
    const names = configService.projectNames
    const selection = await this.interactor.chooseOption(
      [...names, "new"],
      "Selection: ",
      "Choose an existing project or select \"new\" to create one",
    )
    if (selection === "new") {
      return addProject(this.interactor, this.username)
    }
    try {
      return await selectProject(selection, this.interactor, this.username)
    } catch (_) {
      this.interactor.error("Invalid project selection")
      return null
    }
  }
}
