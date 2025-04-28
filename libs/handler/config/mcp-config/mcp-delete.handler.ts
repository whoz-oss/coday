import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { McpServerConfig } from '../../../model/mcp-server-config'
import { getMcpConfigNameAndId } from './helpers'

export class McpDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete an MCP server configuration.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommands = this.getSubCommand(command).split(' ')

    // Check if project level or user level
    const isProjectLevel = subCommands.filter((c) => c === '--project' || c === '-p').length > 0

    // Detect the name of the server or option
    const names = subCommands.filter((c) => !c.startsWith('-'))
    const name = names.length ? names[0].toLowerCase() : undefined

    // Find matching servers
    const matching = this.service
      .getServers(isProjectLevel)
      .filter((s) => name && s.name.toLowerCase().startsWith(name))

    let serverConfig: McpServerConfig | undefined

    // If multiple servers match, choose one
    if (matching.length > 1) {
      const matchingPerNameAndId = new Map<string, McpServerConfig>(
        matching.map((server) => [getMcpConfigNameAndId(server), server])
      )
      const choice = await this.interactor.chooseOption(
        Array.from(matchingPerNameAndId.keys()).sort(),
        'Select the MCP config to remove'
      )
      serverConfig = matchingPerNameAndId.get(choice)
    }

    // If single server, selection is done
    if (matching.length === 1) {
      serverConfig = matching[0]
    }

    // If no server was found or selected
    if (!serverConfig) {
      this.interactor.displayText('Could not find an MCP server matching the provided name.')
      return context
    }

    // Confirm removal
    const levelName = isProjectLevel ? 'project-level' : 'user-level'

    // Remove the server
    await this.service.removeServer(serverConfig.id, isProjectLevel)
    this.interactor.displayText(`Removed ${levelName} MCP server: ${serverConfig.name} (${serverConfig.id})`)

    return context
  }
}
