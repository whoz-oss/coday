import {CommandContext, Interactor} from "../../model"
import {configService} from "../../service/config.service"
import {loadOrInitProjectDescription} from "../../service/load-or-init-project-description"
import {ProjectSelectedEvent} from "../../shared"

export async function selectProject(
  projectName: string,
  interactor: Interactor,
  username: string,
): Promise<CommandContext | null> {
  if (!projectName && !configService.project) {
    interactor.error("No project selected nor known.")
    return null
  }
  let projectPath: string
  try {
    const paths = configService.selectProjectAndGetProjectPath(projectName)
    projectPath = paths.projectPath
    interactor.displayText(`Project local configuration used: ${paths.projectConfigFolderPath}`)
    interactor.sendEvent(new ProjectSelectedEvent({projectName: projectName}))
  } catch (err: any) {
    interactor.error(err.message)
    return null
  }
  if (!projectPath) {
    interactor.error(`No path found to project ${projectName}`)
    return null
  }
  
  const projectConfig = await loadOrInitProjectDescription(
    projectPath,
    interactor,
    username,
  )
  
  return new CommandContext(
    {
      ...projectConfig,
      root: projectPath,
      name: projectName
    },
    username,
  )
}
