import {Interactor} from "../../model/interactor"
import {configService} from "../../service/config.service"
import {CommandContext} from "../../model/command-context"
import {selectProject} from "./select-project"

export async function addProject(
  interactor: Interactor,
  username: string,
): Promise<CommandContext | null> {
  const projectName = await interactor.promptText("Project name")
  const projectPath = await interactor.promptText(
    "Project path, no trailing slash",
  )
  configService.addProject(projectName, projectPath)
  return await selectProject(projectName, interactor, username)
}
