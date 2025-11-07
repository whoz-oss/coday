import express from 'express'
import { debugLog } from './log'
import { AgentService } from '@coday/agent/agent.service'
import { CommandContext } from '@coday/model'
import { ProjectService } from './services/project.service'
import { ServerInteractor } from '@coday/model/server-interactor'
import { UserService } from '@coday/service/user.service'
import { AiClientProvider } from '@coday/integration/ai/ai-client-provider'
import { CodayServices } from '@coday/coday-services'
import { ProjectStateService } from '@coday/service/project-state.service'
import { IntegrationService } from '@coday/service/integration.service'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { MemoryService } from '@coday/service/memory.service'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { CodayLogger } from '@coday/service/coday-logger'
import { WebhookService } from '@coday/service/webhook.service'
import { ThreadService } from './services/thread.service'
import { CodayOptions } from '@coday/options'
import { AgentSummary } from '@coday/model'

/**
 * Agent Management REST API Routes
 *
 * This module provides REST endpoints for agent-related operations,
 * particularly for autocomplete functionality in the UI.
 *
 * Endpoints:
 * - GET /api/agents/autocomplete?query=ag - Get agents matching query for autocomplete
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
   * GET /api/agents/autocomplete
   * Get list of agents matching query for autocomplete
   *
   * Query params:
   * - query: string (optional) - Filter agents by name prefix
   * - project: string (required) - Project name
   *
   * Returns: Array<{ name: string, description: string }>
   */
  app.get('/api/agents/autocomplete', async (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      const { query = '', project } = req.query

      if (!project || typeof project !== 'string') {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('AGENT', `GET autocomplete: query="${query}", project="${project}", user="${username}"`)

      // Get project from ProjectService
      const projectData = projectService.getProject(project)
      if (!projectData) {
        res.status(404).json({ error: `Project '${project}' not found` })
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

      const services: CodayServices = {
        user,
        project: projectState,
        integration,
        integrationConfig,
        memory,
        mcp,
        thread: threadService,
        logger,
        webhook: webhookService,
      }

      // Create temporary AiClientProvider and AgentService
      const aiClientProvider = new AiClientProvider(interactor, services)
      const agentService = new AgentService(
        interactor,
        aiClientProvider,
        services,
        projectData.config.path,
        options.agentFolders
      )

      // Select the project in the state service
      projectState.selectProject(project)

      // Create a minimal command context
      const context: CommandContext = {
        project: projectData.config,
        oneshot: true,
      } as CommandContext

      // Initialize and get agent summaries
      await agentService.initialize(context)
      const allAgents = agentService.listAgentSummaries()

      // Filter by query if provided
      const filteredAgents =
        query && typeof query === 'string'
          ? allAgents.filter((agent: AgentSummary) => agent.name.toLowerCase().startsWith(query.toLowerCase()))
          : allAgents

      debugLog('AGENT', `Found ${filteredAgents.length} agents matching "${query}"`)

      // Cleanup
      await agentService.kill()

      res.status(200).json(filteredAgents)
    } catch (error) {
      console.error('Error in agent autocomplete:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to get agent autocomplete: ${errorMessage}` })
    }
  })
}
