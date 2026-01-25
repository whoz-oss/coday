import { CommandContext, CommandHandler, Interactor } from '@coday/model'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { IntegrationConfig, IntegrationLocalConfig } from '@coday/model'
import { ConfigLevel } from '@coday/model/config-level'
import { parseArgs } from '../parse-args'

export class IntegrationListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: IntegrationConfigService
  ) {
    super({
      commandWord: 'list',
      description:
        'Lists integration configurations from all levels (PROJECT, USER). Use --merged to show final merged configuration.',
    })
  }

  /**
   * Format the integration configs for a specific level
   * @param level The configuration level
   * @param integrations The integration configurations
   * @returns Formatted markdown text for display
   */
  private formatIntegrationConfigs(level: ConfigLevel, integrations: IntegrationLocalConfig): string {
    const levelName = level.toLowerCase()
    let content = `## ${level} Level\n\n`

    const integrationNames = Object.keys(integrations)

    if (integrationNames.length === 0) {
      content += `*No integrations defined at ${levelName} level.*\n\n`

      // Add helpful commands based on level
      if (level === ConfigLevel.PROJECT) {
        content += `To add a project-level integration, use: \`config integration add --project\`\n`
      } else if (level === ConfigLevel.USER) {
        content += `To add a user-level integration, use: \`config integration add\`\n`
      }
    } else {
      content += `**${integrationNames.length} integration${integrationNames.length === 1 ? '' : 's'} configured:**\n\n`

      const integrationConfigs = integrationNames
        .sort()
        .map((name) => {
          const config = integrations[name]!
          return this.formatSingleIntegration(name, config)
        })
        .join('\n\n')

      content += integrationConfigs + '\n'
    }

    return content
  }

  /**
   * Format a single integration configuration
   * @param name The integration name
   * @param config The integration configuration
   * @returns Formatted text
   */
  private formatSingleIntegration(name: string, config: IntegrationConfig): string {
    const lines: string[] = []
    lines.push(`**${name}:**`)

    if (config.apiUrl) {
      lines.push(`  - API URL: \`${config.apiUrl}\``)
    }

    if (config.username) {
      lines.push(`  - Username: \`${config.username}\``)
    }

    // API Key - show "Set" or "Not set"
    const apiKeyStatus = config.apiKey ? 'Set' : 'Not set'
    lines.push(`  - API Key: ${apiKeyStatus}`)

    return lines.join('\n')
  }

  /**
   * Format the merged configuration
   * @param integrations The merged integration configurations
   * @returns Formatted markdown text for display
   */
  private formatMergedConfig(integrations: IntegrationLocalConfig): string {
    let content = `# MERGED Configuration\n\n`

    const integrationNames = Object.keys(integrations)

    if (integrationNames.length === 0) {
      content += `*No integrations available after merging.*\n`
    } else {
      content += `**${integrationNames.length} integration${integrationNames.length === 1 ? '' : 's'} available:**\n\n`

      const integrationConfigs = integrationNames
        .sort()
        .map((name) => {
          const config = integrations[name]!
          return this.formatSingleIntegration(name, config)
        })
        .join('\n\n')

      content += integrationConfigs + '\n'
    }

    return content
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments to check for --merged flag
    const args = parseArgs(this.getSubCommand(command), [{ key: 'merged', alias: 'm' }])

    const showMerged = !!args.merged

    if (showMerged) {
      // Show only the merged configuration
      const mergedConfig = this.service.getMergedIntegrations()
      this.interactor.displayText(this.formatMergedConfig(mergedConfig))
    } else {
      // Show all levels individually
      const projectIntegrations = this.service.getIntegrations(ConfigLevel.PROJECT)
      const userIntegrations = this.service.getIntegrations(ConfigLevel.USER)

      const projectOutput = this.formatIntegrationConfigs(ConfigLevel.PROJECT, projectIntegrations)
      const userOutput = this.formatIntegrationConfigs(ConfigLevel.USER, userIntegrations)

      // Build complete output
      let output = `# Integration Configurations\n\n`
      output += projectOutput + '\n'
      output += userOutput + '\n'

      // Add summary
      const mergedConfig = this.service.getMergedIntegrations()
      const totalMerged = Object.keys(mergedConfig).length
      const totalDefined = Object.keys(projectIntegrations).length + Object.keys(userIntegrations).length

      output += `## Summary\n\n`
      output += `- **Total integrations defined:** ${totalDefined}\n`
      output += `- **Total integrations after merging:** ${totalMerged}\n`

      if (totalMerged < totalDefined) {
        output += `\n*Note: Some integrations were merged due to same name across levels.*\n`
      }

      output += `\nUse \`config integration list --merged\` to see the final merged configuration.\n`

      this.interactor.displayText(output)
    }

    return context
  }
}
