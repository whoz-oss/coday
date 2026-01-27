import { CommandHandler, parseArgs } from '@coday/handler'
import { CommandContext } from '@coday/model'
import { Interactor } from '@coday/model'
import { McpConfigService } from '@coday/service'
import { ConfigLevel } from '@coday/model'
import { McpServerConfig } from '@coday/model'
import { formatMcpConfig, sanitizeMcpServerConfig } from './helpers'

export class McpListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'list',
      description:
        'Lists MCP server configurations from all levels (CODAY, PROJECT, USER). Use --merged to show final merged configuration.',
    })
  }

  /**
   * Format the MCP server configs for a specific level
   * @param level The configuration level
   * @param servers The list of server configurations
   * @returns Formatted markdown text for display
   */
  private formatServerConfigs(level: ConfigLevel, servers: McpServerConfig[]): string {
    const levelName = level.toLowerCase()
    let content = `## ${level} Level\n\n`

    if (servers.length === 0) {
      content += `*No MCP servers defined at ${levelName} level.*\n\n`

      // Add helpful commands based on level
      if (level === ConfigLevel.PROJECT) {
        content += `To add a project-level MCP server, use: \`config mcp add --project\`\n`
      } else if (level === ConfigLevel.USER) {
        content += `To add a user-level MCP server, use: \`config mcp add\`\n`
      }
    } else {
      content += `**${servers.length} server${servers.length === 1 ? '' : 's'} configured:**\n\n`

      const serverConfigs = servers
        .map((server) => {
          const sanitized = sanitizeMcpServerConfig(server)
          return this.indentConfigAsMarkdown(formatMcpConfig(sanitized))
        })
        .join('\n\n')

      content += serverConfigs + '\n'
    }

    return content
  }

  /**
   * Format the merged configuration
   * @param servers The merged server configurations
   * @returns Formatted markdown text for display
   */
  private formatMergedConfig(servers: McpServerConfig[]): string {
    let content = `# MERGED Configuration\n\n`

    if (servers.length === 0) {
      content += `*No MCP servers available after merging.*\n`
    } else {
      content += `**${servers.length} server${servers.length === 1 ? '' : 's'} available:**\n\n`

      const serverConfigs = servers
        .map((server) => {
          const sanitized = sanitizeMcpServerConfig(server)
          return this.indentConfigAsMarkdown(formatMcpConfig(sanitized))
        })
        .join('\n\n')

      content += serverConfigs + '\n'
    }

    return content
  }

  /**
   * Indent configuration text as markdown code block
   * @param configText The configuration text to indent
   * @returns Markdown formatted text
   */
  private indentConfigAsMarkdown(configText: string): string {
    return '```\n' + configText + '\n```'
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments to check for --merged flag
    const args = parseArgs(this.getSubCommand(command), [{ key: 'merged', alias: 'm' }])

    const showMerged = !!args.merged

    if (showMerged) {
      // Show only the merged configuration
      const mergedConfig = this.service.getMergedConfiguration()
      this.interactor.displayText(this.formatMergedConfig(mergedConfig.servers))
    } else {
      // Show all levels individually
      const codayServers = this.service.getMcpServers(ConfigLevel.CODAY)
      const projectServers = this.service.getMcpServers(ConfigLevel.PROJECT)
      const userServers = this.service.getMcpServers(ConfigLevel.USER)

      const codayOutput = this.formatServerConfigs(ConfigLevel.CODAY, codayServers)
      const projectOutput = this.formatServerConfigs(ConfigLevel.PROJECT, projectServers)
      const userOutput = this.formatServerConfigs(ConfigLevel.USER, userServers)

      // Build complete output
      let output = `# MCP Server Configurations\n\n`
      output += codayOutput + '\n'
      output += projectOutput + '\n'
      output += userOutput + '\n'

      // Add summary
      const mergedConfig = this.service.getMergedConfiguration()
      const totalMerged = mergedConfig.servers.length
      const totalDefined = codayServers.length + projectServers.length + userServers.length

      output += `## Summary\n\n`
      output += `- **Total servers defined:** ${totalDefined}\n`
      output += `- **Total servers after merging:** ${totalMerged}\n`

      if (totalMerged < totalDefined) {
        output += `\n*Note: Some servers were merged due to same ID across levels.*\n`
      }

      output += `\nUse \`config mcp list --merged\` to see the final merged configuration.\n`

      this.interactor.displayText(output)
    }

    return context
  }
}
