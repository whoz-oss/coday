import express from 'express'
import { debugLog } from './log'
import { CommandContext, Project } from '@coday/model'
import { ProjectService } from '@coday/service'
import { ServerInteractor } from '@coday/model'
import { UserService } from '@coday/service'
import { CodayServices } from '@coday/coday-services'
import { ProjectStateService } from '@coday/service'
import { IntegrationService } from '@coday/service'
import { IntegrationConfigService } from '@coday/service'
import { MemoryService } from '@coday/service'
import { McpConfigService } from '@coday/service'
import { CodayLogger } from '@coday/model'
import { CodayOptions } from '@coday/model'
import { loadOrInitProjectDescription } from '@coday/service'
import { McpInstancePool } from '@coday/mcp'
import { AgentService } from '@coday/agent'
import { AiClientProvider } from '@coday/integrations-ai'
import { ThreadService, PromptService, AgentCrudService } from '@coday/service'
import type { AgentDefinition } from '@coday/model'
import type { AgentLocation } from '@coday/service'
import { getParamAsString } from './route-helpers'

/**
 * Agent Management REST API Routes
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/agents                          - List all agents (all sources, for autocomplete)
 * - GET    /api/projects/:projectName/agents/documents?location=...   - List documents pool
 * - POST   /api/projects/:projectName/agents/documents?location=...   - Upload document to pool
 * - GET    /api/projects/:projectName/agents/editable                 - List editable agents (file-based only)
 * - GET    /api/projects/:projectName/agents/:agentName               - Get agent definition
 * - POST   /api/projects/:projectName/agents                          - Create new agent (file-based)
 * - PUT    /api/projects/:projectName/agents/:agentName               - Update agent (file-based)
 * - DELETE /api/projects/:projectName/agents/:agentName               - Delete agent (file-based)
 */
export function registerAgentRoutes(
  app: express.Application,
  projectService: ProjectService,
  getUsernameFn: (req: express.Request) => string,
  configDir: string,
  logger: CodayLogger,
  promptService: PromptService,
  threadService: ThreadService,
  options: CodayOptions
): void {
  const agentCrudService = new AgentCrudService(configDir, projectService)

  /**
   * GET /api/projects/:projectName/agents
   * List all agents for a project (all sources, for autocomplete and display)
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

      const projectData = projectService.getProject(<string>projectName)
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
        prompt: promptService,
        logger,
        options,
      }

      projectState.selectProject(<string>projectName)

      const aiClientProvider = new AiClientProvider(
        interactor,
        user,
        projectState.selectedProject?.config?.ai || [],
        logger
      )
      const agentService = new AgentService(
        interactor,
        aiClientProvider,
        services,
        projectData.config.path,
        options.agentFolders
      )

      const projectDescription = await loadOrInitProjectDescription(projectData.config.path, interactor, {
        username,
        bio: user.config.bio,
      })

      const projectObj: Project = {
        ...projectDescription,
        root: projectData.config.path,
        name: projectName as string,
      }

      const context = new CommandContext(projectObj, username)
      context.oneshot = true

      aiClientProvider.init(context)
      await agentService.initialize(context)
      const allAgents = agentService.listAgentSummaries()

      await agentService.kill()

      res.status(200).json(allAgents)
    } catch (error) {
      console.error('Error listing agents:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list agents: ${errorMessage}` })
    }
  })

  /**
   * GET /api/projects/:projectName/agents/documents?location=project|colocated
   * List files available in the documents pool for a given location
   */
  app.get('/api/projects/:projectName/agents/documents', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const location: AgentLocation = req.query['location'] === 'colocated' ? 'colocated' : 'project'
      debugLog('AGENT', `GET documents pool: project="${projectName}", location="${location}"`)

      const files = await agentCrudService.listDocuments(projectName, location)
      res.status(200).json(files)
    } catch (error) {
      console.error('Error listing agent documents:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list documents: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/agents/documents?location=project|colocated
   * Upload a document into the pool
   * Body: { filename: string, content: string (base64), mimeType: string }
   * Returns: { relativePath: string } — path to use in mandatoryDocs
   */
  app.post('/api/projects/:projectName/agents/documents', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const location: AgentLocation = req.query['location'] === 'colocated' ? 'colocated' : 'project'
      const { filename, content } = req.body as { filename?: string; content?: string; mimeType?: string }

      if (!filename || !content) {
        res.status(400).json({ error: 'filename and content (base64) are required' })
        return
      }

      const buffer = Buffer.from(content, 'base64')
      debugLog('AGENT', `POST upload document: "${filename}" in ${location} for project="${projectName}"`)

      const relativePath = await agentCrudService.saveDocument(projectName, location, filename, buffer)
      res.status(201).json({ relativePath })
    } catch (error) {
      console.error('Error uploading agent document:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      const status = errorMessage.includes('too large') || errorMessage.includes('not allowed') ? 422 : 500
      res.status(status).json({ error: errorMessage })
    }
  })

  /**
   * GET /api/projects/:projectName/agents/editable
   * List only file-based (editable) agents with their source metadata
   */
  app.get('/api/projects/:projectName/agents/editable', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('AGENT', `GET editable agents: project="${projectName}"`)

      const agents = await agentCrudService.list(projectName)
      res.status(200).json(agents)
    } catch (error) {
      console.error('Error listing editable agents:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list editable agents: ${errorMessage}` })
    }
  })

  /**
   * GET /api/projects/:projectName/agents/:agentName
   * Get a specific agent definition with source metadata
   */
  app.get('/api/projects/:projectName/agents/:agentName', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const agentName = getParamAsString(req.params.agentName)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }
      if (!agentName) {
        res.status(400).json({ error: 'Agent name is required' })
        return
      }

      debugLog('AGENT', `GET agent: "${agentName}" in project="${projectName}"`)

      const agent = await agentCrudService.get(projectName, agentName)
      if (!agent) {
        res.status(404).json({ error: `Agent '${agentName}' not found` })
        return
      }

      res.status(200).json(agent)
    } catch (error) {
      console.error('Error getting agent:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to get agent: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/agents
   * Create a new file-based agent
   * Body: { location: 'project' | 'colocated', definition: AgentDefinition }
   */
  app.post('/api/projects/:projectName/agents', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const { location, definition } = req.body as { location?: AgentLocation; definition?: AgentDefinition }

      if (!definition || typeof definition !== 'object') {
        res.status(422).json({ error: 'Agent definition is required' })
        return
      }
      if (!definition.name || typeof definition.name !== 'string') {
        res.status(422).json({ error: 'Agent name is required' })
        return
      }
      if (!definition.description || typeof definition.description !== 'string') {
        res.status(422).json({ error: 'Agent description is required' })
        return
      }

      const agentLocation: AgentLocation = location === 'colocated' ? 'colocated' : 'project'

      debugLog('AGENT', `POST create agent: "${definition.name}" in ${agentLocation} for project="${projectName}"`)

      const created = await agentCrudService.create(projectName, definition, agentLocation)
      res.status(201).json(created)
    } catch (error) {
      console.error('Error creating agent:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'

      if (errorMessage.includes('already exists') || errorMessage.includes('must start with')) {
        res.status(422).json({ error: errorMessage })
        return
      }

      res.status(500).json({ error: `Failed to create agent: ${errorMessage}` })
    }
  })

  /**
   * PUT /api/projects/:projectName/agents/:agentName
   * Update an existing file-based agent
   * Body: { definition: AgentDefinition }
   */
  app.put('/api/projects/:projectName/agents/:agentName', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const agentName = getParamAsString(req.params.agentName)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }
      if (!agentName) {
        res.status(400).json({ error: 'Agent name is required' })
        return
      }

      const { definition } = req.body as { definition?: AgentDefinition }

      if (!definition || typeof definition !== 'object') {
        res.status(422).json({ error: 'Agent definition is required' })
        return
      }

      debugLog('AGENT', `PUT update agent: "${agentName}" in project="${projectName}"`)

      const updated = await agentCrudService.update(projectName, agentName, definition)
      if (!updated) {
        res.status(404).json({ error: `Agent '${agentName}' not found` })
        return
      }

      res.status(200).json(updated)
    } catch (error) {
      console.error('Error updating agent:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'

      if (errorMessage.includes('cannot be empty')) {
        res.status(422).json({ error: errorMessage })
        return
      }

      res.status(500).json({ error: `Failed to update agent: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:projectName/agents/:agentName
   * Delete a file-based agent
   */
  app.delete('/api/projects/:projectName/agents/:agentName', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const agentName = getParamAsString(req.params.agentName)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }
      if (!agentName) {
        res.status(400).json({ error: 'Agent name is required' })
        return
      }

      debugLog('AGENT', `DELETE agent: "${agentName}" in project="${projectName}"`)

      const deleted = await agentCrudService.delete(projectName, agentName)
      if (!deleted) {
        res.status(404).json({ error: `Agent '${agentName}' not found` })
        return
      }

      res.status(200).json({ success: true, message: `Agent '${agentName}' deleted successfully` })
    } catch (error) {
      console.error('Error deleting agent:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to delete agent: ${errorMessage}` })
    }
  })
}
