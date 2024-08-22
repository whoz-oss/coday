import {CommandContext, Interactor} from "../../model"
import {configService} from "../../service/config.service"
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
