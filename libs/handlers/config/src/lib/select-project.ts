import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { CommandContext } from '@coday/model'
import { UserData } from '@coday/model'
import { loadOrInitProjectDescription } from '@coday/service'

export async function buildFirstCommandContext(
  interactor: Interactor,
  services: CodayServices
): Promise<CommandContext> {
  const project = services.project.selectedProject!
  const userData: UserData = services.user.getUserData(project.name)
  const projectConfig = await loadOrInitProjectDescription(project.config.path, interactor, userData)

  const context = new CommandContext(
    {
      ...projectConfig,
      root: project.config.path,
      name: project.name,
    },
    services.user.username
  )
  console.log(
    `[BUILD_CONTEXT] Context for '${context.project.name}' → root: ${context.project.root}, desc: ${context.project.description?.length || 0} chars`
  )
  return context
}
