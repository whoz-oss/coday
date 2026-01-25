import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { CommandContext } from '@coday/handler'
import { UserData } from '@coday/model/user-data'
import { loadOrInitProjectDescription } from '@coday/service/load-or-init-project-description'

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
