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
    this.interactor.displayText(
      `MCP configs at project level:
${this.service
  .getServers(true)
  .map((server) => formatMcpConfig(sanitizeMcpServerConfig(server)))
  .join(`\n\n`)}

MCP configs at user level:
${this.service
  .getServers(false)
  .map((server) => formatMcpConfig(sanitizeMcpServerConfig(server)))
  .join(`\n\n`)}`
    )
    return context
  }
}
