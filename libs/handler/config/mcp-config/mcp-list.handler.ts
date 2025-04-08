import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { formatMcpConfig, sanitizeMcpServerConfig } from './helpers'

export class McpListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'list',
      description: 'Lists the current mcp configs at bothe project and user level',
    })
  }

  /**
   * Format the MCP server configs for a specific level (project or user)
   * @param isProjectLevel Whether this is for project level configs
   * @param servers The list of server configurations
   * @returns Formatted text for display
   */
  private formatServerConfigs(isProjectLevel: boolean, servers: any[]): string {
    const level = isProjectLevel ? 'project' : 'user'
    const addCommand = isProjectLevel ? 'config mcp add --project' : 'config mcp add'
    
    let outputText = `MCP configs at ${level} level:`
    
    if (servers.length === 0) {
      outputText += `\n  No MCP configs found at ${level} level.\n  To add a ${level}-level MCP server, use: ${addCommand}`
    } else {
      outputText += '\n' + servers
        .map((server) => formatMcpConfig(sanitizeMcpServerConfig(server)))
        .join('\n\n')
    }
    
    return outputText
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const projectServers = this.service.getServers(true)
    const userServers = this.service.getServers(false)
    
    const projectOutput = this.formatServerConfigs(true, projectServers)
    const userOutput = this.formatServerConfigs(false, userServers)
    
    this.interactor.displayText(`${projectOutput}\n\n${userOutput}`)
    return context
  }
}
