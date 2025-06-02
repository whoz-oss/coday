import { CommandContext, CommandHandler } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { parseArgs } from '../parse-args'

export class UserBioHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'bio',
      description: `Edit user bio information for agent context.\n    --user: Set your bio across ALL projects\n    --project: Set your bio for THIS project only (personal, not shared)\n    Default: --project (most common use case)\n    Example: \`config bio --user\`, \`config bio --project\`.\n    Shorthand syntax: \`config bio -u\`, \`config bio -p\`.`
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    const args = parseArgs(subCommand, [
      { key: 'project', alias: 'p' },
      { key: 'user', alias: 'u' },
    ])

    // Bio defaults to PROJECT level (unlike memory) - users typically want project-specific context
    const isUserLevel = args.user && !args.project
    
    if (isUserLevel) {
      return this.handleBio('USER', undefined, context)
    } else {
      const project = this.services.project.selectedProject
      if (!project) {
        this.interactor.displayText('No current project selected. Use "config project" to select one first.')
        return context
      }
      return this.handleBio('PROJECT', project.name, context)
    }
  }

  private async handleBio(level: 'USER' | 'PROJECT', projectName: string | undefined, context: CommandContext): Promise<CommandContext> {
    const isUserLevel = level === 'USER'
    
    // Get current bio
    const currentBio = isUserLevel 
      ? this.services.user.getBio()
      : this.services.user.getProjectBio(projectName!)
    
    // Build prompt message
    let promptMessage: string
    if (isUserLevel) {
      promptMessage = 'Enter your bio for ALL projects (personal, applies everywhere):'
    } else {
      promptMessage = `Enter your bio for project "${projectName}" only (personal, not shared with other users):`
      const userBio = this.services.user.getBio()
      if (userBio) {
        promptMessage += `\n\nNote: Your global bio will also be included:\n"${userBio}"`
      }
    }
    
    // Get new bio from user
    const newBio = await this.interactor.promptText(promptMessage, currentBio || '')

    if (newBio !== null) {
      // Save bio
      if (isUserLevel) {
        this.services.user.setBio(newBio)
      } else {
        this.services.user.setProjectBio(projectName!, newBio)
      }
      
      // Provide feedback
      const levelLabel = isUserLevel ? 'USER-level' : `PROJECT-level for "${projectName}"`
      if (newBio.trim()) {
        this.interactor.displayText(`✅ ${levelLabel} bio updated successfully`)
      } else {
        this.interactor.displayText(`✅ ${levelLabel} bio cleared`)
      }
    }

    return context
  }
}
