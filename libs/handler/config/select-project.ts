import { CommandContext, Interactor } from '../../model'
import { loadOrInitProjectDescription } from '../../service/load-or-init-project-description'
import { CodayServices } from '../../coday-services'
import { UserData } from '../../model/user-data'

export async function buildFirstCommandContext(
  interactor: Interactor,
  services: CodayServices
): Promise<CommandContext> {
  const project = services.project.selectedProject!
  const userData: UserData = services.user.getUserData(project.name)  // Pass project name
  const projectConfig = await loadOrInitProjectDescription(project.config.path, interactor, userData)

  return new CommandContext(
    {
      ...projectConfig,
      root: project.config.path,
      name: project.name,
    },
    services.user.username
  )
}
