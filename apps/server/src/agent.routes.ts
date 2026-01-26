import express from 'express'
import { debugLog } from './log'
import { CommandContext } from '@coday/handler'
import { ProjectService } from '@coday/service'
import { ServerInteractor } from '@coday/model'
import { UserService } from '@coday/service'
import { CodayServices } from '@coday/coday-services'
import { ProjectStateService } from '@coday/service'
import { IntegrationService } from '@coday/service'
import { IntegrationConfigService } from '@coday/service'
import { MemoryService } from '@coday/service'
import { McpConfigService } from '@coday/service'
import { CodayLogger } from '@coday/service'
import { WebhookService } from '@coday/service'
import { CodayOptions } from '../../../libs/model/src/lib/coday-options'
import { loadOrInitProjectDescription } from '@coday/service'
import { McpInstancePool } from '@coday/mcp'
import { AgentService } from '@coday/agent'
import { AiClientProvider } from '@coday/integrations-ai'
import { ThreadService } from 'libs/service/src/lib/thread.service'

/**
 * Agent Management REST API Routes
 *
 * This module provides REST endpoints for agent-related operations.
 *
 * Endpoints:
 * - GET /api/projects/:projectName/agents - List all agents for a project
 */

/**
 * Register agent routes on the Express app
 * @param app - Express application instance
 * @param projectService - ProjectService instance
 * @param getUsernameFn - Function to extract username from request
 * @param configDir - Configuration directory path
 * @param logger - Coday logger instance
 * @param webhookService - Webhook service instance
 * @param threadService - Thread service instance
 * @param options - Coday options
 */
export function registerAgentRoutes(
  app: express.Application,
  projectService: ProjectService,
  getUsernameFn: (req: express.Request) => string,
  configDir: string,
  logger: CodayLogger,
  webhookService: WebhookService,
  threadService: ThreadService,
  options: CodayOptions
): void {
  /**
   * GET /api/projects/:projectName/agents
   * List all agents for a project
   *
   * Returns: Array<{ name: string, description: string }>
   */
  app.get('/api/projects/:projectName/agents', async (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      const { projectName } = req.params

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('AGENT', `GET agents list: project="${projectName}", user="${username}"`)

      // Get project from ProjectService
      const projectData = projectService.getProject(projectName)
      if (!projectData) {
        res.status(404).json({ error: `Project '${projectName}' not found` })
        return
      }

      // Create temporary services for agent listing
      const interactor = new ServerInteractor('agent-autocomplete')
      const user = new UserService(configDir, username, interactor)
      const projectState = new ProjectStateService(interactor, projectService, configDir)
      const integration = new IntegrationService(projectState, user)
      const integrationConfig = new IntegrationConfigService(user, projectState, interactor)
      const memory = new MemoryService(projectState, user)
      const mcp = new McpConfigService(user, projectState, interactor)

      // Create a temporary MCP pool for this request (won't be used but required by CodayServices)
      const mcpPool = new McpInstancePool()

      const services: CodayServices = {
        user,
        project: projectState,
        integration,
        integrationConfig,
        memory,
        mcp,
        mcpPool,
        thread: threadService,
        logger,
        webhook: webhookService,
        options,
      }

      // Select the project in the state service
      projectState.selectProject(projectName)

      // Create temporary AiClientProvider and AgentService
      const aiClientProvider = new AiClientProvider(interactor, user, projectState, logger)
      const agentService = new AgentService(
        interactor,
        aiClientProvider,
        services,
        projectData.config.path,
        options.agentFolders
      )

      // Load the real project description from coday.yaml
      const projectDescription = await loadOrInitProjectDescription(projectData.config.path, interactor, {
        username,
        bio: user.config.bio,
      })

      // Create a Project object (ProjectDescription + root + name)
      const projectObj = {
        ...projectDescription,
        root: projectData.config.path,
        name: projectName,
      }

      // Create a command context with the full project object
      const context = new CommandContext(projectObj, username)
      context.oneshot = true

      // Initialize and get agent summaries
      debugLog('AGENT', `Initializing AgentService with projectPath: ${projectData.config.path}`)
      debugLog('AGENT', `Project config agents: ${projectData.config.agents?.length || 0}`)
      debugLog('AGENT', `AgentFolders from options: ${JSON.stringify(options.agentFolders)}`)

      // Initialize AI client provider before agent service
      aiClientProvider.init(context)

      await agentService.initialize(context)
      const allAgents = agentService.listAgentSummaries()

      debugLog('AGENT', `Total agents loaded: ${allAgents.length}`)
      debugLog('AGENT', `Agent names: ${allAgents.map((a) => a.name).join(', ')}`)

      // Cleanup
      await agentService.kill()

      res.status(200).json(allAgents)
    } catch (error) {
      console.error('Error in agent autocomplete:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to get agent autocomplete: ${errorMessage}` })
    }
  })
}
