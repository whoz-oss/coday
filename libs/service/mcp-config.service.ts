import { Interactor } from '../model/interactor'
import { UserService } from './user.service'
import { ProjectService } from './project.service'
import { McpServerConfig } from '../model/mcp-server-config'
import { ConfigLevel, ConfigLevelValidator } from '../model/config-level'
import { CommandContext } from '../model'
import { mergeMcpConfigs } from './mcp-config-merger'

/**
 * Represents the combined MCP configuration from all levels
 */
export interface McpConfiguration {
  /**
   * All available MCP servers after merging configurations
   */
  servers: McpServerConfig[]
}

/**
 * Service for managing MCP (Model Context Protocol) configurations at all levels:
 * - coday.yaml (global defaults)
 * - Project level (project-specific settings)
 * - User level (user-specific settings)
 */
export class McpConfigService {
  private mcpCache: Map<ConfigLevel, McpServerConfig[]> = new Map()
  private mergedConfig: McpConfiguration | null = null

  constructor(
    private userService: UserService,
    private projectService: ProjectService,
    private interactor: Interactor
  ) {}

  /**
   * Initialize the service with the current context
   */
  initialize(context: CommandContext): void {
    this.mcpCache.clear()
    this.mergedConfig = null

    // Cache configurations at each level for faster access
    const codayServers = context.project.mcp?.servers || []
    const projectServers = this.projectService.selectedProject?.config?.mcp?.servers || []
    const userServers = this.getUserMcpServers()

    this.mcpCache.set(ConfigLevel.CODAY, codayServers)
    this.mcpCache.set(ConfigLevel.PROJECT, projectServers)
    this.mcpCache.set(ConfigLevel.USER, userServers)
  }

  /**
   * Get all MCP server configurations at a specific level
   */
  getMcpServers(level: ConfigLevel): McpServerConfig[] {
    return this.mcpCache.get(level) || []
  }

  /**
   * Save an MCP server configuration
   */
  async saveMcpServer(config: McpServerConfig, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)
    const mcpServers = this.getMcpServers(level)
    const index = mcpServers.findIndex((mcp) => mcp.id === config.id)

    if (index >= 0) {
      mcpServers[index] = config
    } else {
      mcpServers.push(config)
    }

    await this.saveMcpServers(mcpServers, level)
    this.mergedConfig = null // Invalidate cache
  }

  /**
   * Get the merged configuration from all levels with specific merging rules
   */
  getMergedConfiguration(): McpConfiguration {
    if (this.mergedConfig) {
      return this.mergedConfig
    }

    const codayServers = this.mcpCache.get(ConfigLevel.CODAY) || []
    const projectServers = this.mcpCache.get(ConfigLevel.PROJECT) || []
    const userServers = this.mcpCache.get(ConfigLevel.USER) || []

    const merged = mergeMcpConfigs(codayServers, projectServers, userServers)

    // Validate merged servers
    const validatedServers = merged.filter((server) => {
      if (!server.command && !server.url) {
        this.interactor.warn(
          `MCP server '${server.name}' (${server.id}) has no command or url specified. This server will be skipped.`
        )
        return false
      }
      return true
    })

    this.interactor.debug(`MCP merged configuration: ${validatedServers.length} servers total`)
    validatedServers.forEach((server) => {
      this.interactor.debug(
        `  - ${server.name} (${server.id}): enabled=${server.enabled}, debug=${server.debug}, command=${server.command || 'N/A'}, args=[${(server.args || []).join(', ')}]`
      )
    })

    this.mergedConfig = { servers: validatedServers }
    return this.mergedConfig
  }

  /**
   * Remove an MCP server configuration (backward compatibility method)
   * @param mcpId ID of the MCP server to remove
   * @param isProjectLevel Whether to remove from project level instead of user level
   */
  async removeServer(mcpId: string, isProjectLevel: boolean = false): Promise<void> {
    const level = isProjectLevel ? ConfigLevel.PROJECT : ConfigLevel.USER

    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }

    const mcpServers = this.getMcpServers(level)

    // Find MCP server with this ID
    const existingIndex = mcpServers.findIndex((s) => s.id === mcpId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${mcpId}`)
      return
    }

    // Remove MCP server
    mcpServers.splice(existingIndex, 1)

    // Save updated configuration
    await this.saveMcpServers(mcpServers, level)
  }

  /**
   * Get MCP servers from user level for the selected project
   * @private
   */
  private getUserMcpServers(): McpServerConfig[] {
    const project = this.projectService.selectedProject
    if (!project) {
      return []
    }
    const projects = this.userService.config.projects || {}
    return projects[project.name]?.mcp?.servers || []
  }

  /**
   * Save MCP servers at a specific level
   * @private
   */
  private async saveMcpServers(mcpServers: McpServerConfig[], level: ConfigLevel): Promise<void> {
    switch (level) {
      case ConfigLevel.PROJECT:
        if (!this.projectService.selectedProject) {
          throw new Error('No project selected')
        }

        // Update project config
        const updatedProjectConfig = {
          ...this.projectService.selectedProject.config,
          mcp: {
            ...this.projectService.selectedProject.config.mcp,
            servers: mcpServers,
          },
        }

        this.projectService.save(updatedProjectConfig)
        this.mcpCache.set(level, mcpServers)
        break

      case ConfigLevel.USER:
        const project = this.projectService.selectedProject
        if (!project) {
          throw new Error('No project selected')
        }

        const projects = this.userService.config.projects || {}
        let userProjectConfig = projects[project.name]
        if (!userProjectConfig) {
          this.userService.config.projects = this.userService.config.projects || {}
          this.userService.config.projects[project.name] = { integration: {} }
          userProjectConfig = this.userService.config.projects[project.name]
        }

        // Update the MCP configuration while preserving other MCP settings
        userProjectConfig!.mcp = {
          ...userProjectConfig!.mcp,
          servers: mcpServers,
        }

        this.userService.save()
        this.mcpCache.set(level, mcpServers)
        break
    }
  }
}
