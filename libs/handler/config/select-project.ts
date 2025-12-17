import { CommandContext, Interactor } from '@coday/model'
import { loadOrInitProjectDescription } from '@coday/service/load-or-init-project-description'
import { CodayServices } from '../../coday-services'
import { UserData } from '@coday/model/user-data'

export async function buildFirstCommandContext(
  interactor: Interactor,
  services: CodayServices
): Promise<CommandContext> {
  const project = services.project.selectedProject!
  const userData: UserData = services.user.getUserData(project.name) // Pass project name
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
