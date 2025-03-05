import { CommandContext, Interactor } from '../../model'
import { loadOrInitProjectDescription } from '../../service/load-or-init-project-description'
import { CodayServices } from '../../coday-services'

export async function buildFirstCommandContext(
  interactor: Interactor,
  services: CodayServices
): Promise<CommandContext> {
  const project = services.project.selectedProject!
  const projectConfig = await loadOrInitProjectDescription(project.config.path, interactor, services.user.username)

  return new CommandContext(
    {
      ...projectConfig,
      root: project.config.path,
      name: project.name,
    },
    services.user.username
  )
}
