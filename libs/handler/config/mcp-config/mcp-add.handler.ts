import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { McpServerConfig } from '../../../model/mcp-server-config'
import { ConfigLevel } from '../../../model/config-level'
import { parseArgs } from '../../parse-args'
import { McpEditHandler } from './mcp-edit.handler'

/**
 * Handler for adding a new MCP server configuration.
 * Creates a default config and delegates to the edit handler for completion.
 * Follows the add-edit delegation pattern to avoid code duplication.
 */
export class McpAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService,
    private editHandler: McpEditHandler
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new MCP server configuration. User level is default, use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProject = !!args.project
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Get merged configuration to check for duplicates
    const mergedConfig = this.service.getMergedConfiguration()

    // Prompt for server ID and name
    const serverId = await this.interactor.promptText('Enter a unique ID for the new MCP server:')

    if (!serverId.trim()) {
      this.interactor.error('Server ID is required')
      return context
    }

    // Check for duplicate IDs across all levels
    const isDuplicate = mergedConfig.servers.some((s) => s.id === serverId)
    if (isDuplicate) {
      this.interactor.warn(`Server ID '${serverId}' already exists. Cannot add duplicate.`)
      return context
    }

    const serverName = await this.interactor.promptText('Enter a name for the new MCP server:')

    if (!serverName.trim()) {
      this.interactor.error('Server name is required')
      return context
    }

    // Create minimal default config
    const defaultConfig: McpServerConfig = {
      id: serverId,
      name: serverName,
      enabled: true,
      command: '', // Will be filled in edit handler
      args: [],
    }

    // Save the default config at the specified level
    await this.service.saveMcpServer(defaultConfig, level)

    const successMessage = `
# ✅ MCP Server Created

**Server:** ${serverName}  
**ID:** \`${serverId}\`  
**Level:** ${level.toLowerCase()}  
**Status:** Default configuration created

Now completing the configuration...
`
    this.interactor.displayText(successMessage)

    // Delegate to edit handler to complete configuration
    const editCommand = `edit ${serverId} ${isProject ? '--project' : ''}`
    return this.editHandler.handle(editCommand, context)
  }
}
