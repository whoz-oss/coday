import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { McpServerConfig } from '../../../model/mcp-server-config'
import { ConfigLevel } from '../../../model/config-level'
import { parseArgs } from '../../parse-args'
import { getMcpConfigNameAndId } from './helpers'

export class McpDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete an MCP server configuration. Use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProjectLevel = !!args.project
    const searchTerm = args.rest.trim().toLowerCase()

    // Find matching servers
    const level = isProjectLevel ? ConfigLevel.PROJECT : ConfigLevel.USER
    const allServers = this.service.getMcpServers(level)

    const matching = searchTerm
      ? allServers.filter(
          (s: McpServerConfig) => s.name.toLowerCase().includes(searchTerm) || s.id.toLowerCase().includes(searchTerm)
        )
      : allServers

    if (matching.length === 0) {
      const levelName = isProjectLevel ? 'project' : 'user'
      if (searchTerm) {
        this.interactor.warn(`No MCP servers found matching '${searchTerm}' at ${levelName} level.`)
      } else {
        const addCommand = `config mcp add${isProjectLevel ? ' --project' : ''}`
        this.interactor.displayText(
          `No MCP servers configured at ${levelName} level. Use '${addCommand}' to add a server.`
        )
      }
      return context
    }

    let serverConfig: McpServerConfig | undefined

    // If multiple servers match, choose one
    if (matching.length > 1) {
      const matchingPerNameAndId = new Map<string, McpServerConfig>(
        matching.map((server: McpServerConfig) => [getMcpConfigNameAndId(server), server])
      )
      const choice = await this.interactor.chooseOption(
        Array.from(matchingPerNameAndId.keys()).sort(),
        'Select the MCP server to delete:'
      )
      serverConfig = matchingPerNameAndId.get(choice)
    } else {
      // Single server match
      serverConfig = matching[0]
    }

    // If no server was selected
    if (!serverConfig) {
      this.interactor.displayText('No server selected for deletion.')
      return context
    }

    // Confirm removal
    const levelName = isProjectLevel ? 'project' : 'user'
    const confirmMessage = `
# ⚠️ Confirm Deletion

**Server:** ${serverConfig.name}  
**ID:** \`${serverConfig.id}\`  
**Level:** ${levelName}  

Are you sure you want to delete this MCP server configuration?
`
    const confirmAnswer = await this.interactor.chooseOption(
      ['no, keep', 'delete'],
      'Confirm deletion:',
      confirmMessage
    )

    if (confirmAnswer.toLowerCase() !== 'delete') {
      this.interactor.displayText('Deletion cancelled.')
      return context
    }

    // Remove the server
    await this.service.removeServer(serverConfig.id, isProjectLevel)

    const successMessage = `
# ✅ Server Deleted

**Server:** ${serverConfig.name}  
**ID:** \`${serverConfig.id}\`  
**Level:** ${levelName}  
**Status:** Successfully removed from configuration
`
    this.interactor.displayText(successMessage)

    return context
  }
}
