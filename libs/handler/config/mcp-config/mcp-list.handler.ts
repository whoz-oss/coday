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

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const servers = this.service.getServers(false)
    servers.map((server) => formatMcpConfig(sanitizeMcpServerConfig(server))).join(`\n\n`)
    return context
  }
}
