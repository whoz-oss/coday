import express from 'express'
import { debugLog } from './log'
import { ProjectService } from '@coday/service/project.service'
import { ProjectLocalConfig } from '@coday/model/project-local-config'

/**
 * Project Management REST API Routes
 *
 * This module provides REST endpoints for managing projects independently
 * of user sessions, using the stateless ProjectService2.
 *
 * Endpoints:
 * - GET    /api/projects              - List all projects
 * - GET    /api/projects/:name        - Get project details with config
 * - POST   /api/projects              - Create a new project
 * - GET    /api/projects/:name/config - Get project config (masked)
 * - PUT    /api/projects/:name/config - Update project config (unmasked)
 * - DELETE /api/projects/:name        - Delete a project
 */

/**
 * Register project management routes on the Express app
 * @param app - Express application instance
 * @param projectService - ProjectService2 instance for project operations
 */
export function registerProjectRoutes(app: express.Application, projectService: ProjectService): void {
  /**
   * GET /api/projects
   * List all available projects with context metadata
   * Returns: { projects, defaultProject, forcedProject }
   */
  app.get('/api/projects', (_req: express.Request, res: express.Response) => {
    try {
      debugLog('PROJECT', 'GET all projects')
      const projects = projectService.listProjects()
      const defaultProject = projectService.getDefaultProject()
      const isForcedMode = projectService.getForcedMode()

      res.status(200).json({
        projects,
        defaultProject,
        forcedProject: isForcedMode ? defaultProject : null,
      })
    } catch (error) {
      console.error('Error listing projects:', error)
      res.status(500).json({ error: 'Failed to list projects' })
    }
  })

  /**
   * GET /api/projects/:name
   * Get project details including configuration
   */
  app.get('/api/projects/:name', (req: express.Request, res: express.Response) => {
    try {
      const { name } = req.params
      if (!name) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('PROJECT', `GET project: ${name}`)
      const project = projectService.getProject(name)

      if (!project) {
        res.status(404).json({ error: `Project '${name}' not found` })
        return
      }

      res.status(200).json(project)
    } catch (error) {
      console.error('Error retrieving project:', error)
      res.status(500).json({ error: 'Failed to retrieve project' })
    }
  })

  /**
   * POST /api/projects
   * Create a new project
   *
   * Body: { name: string, path: string }
   */
  app.post('/api/projects', (req: express.Request, res: express.Response) => {
    try {
      const { name, path } = req.body

      if (!name || !path) {
        res.status(400).json({ error: 'Project name and path are required' })
        return
      }

      debugLog('PROJECT', `POST create project: ${name} at ${path}`)
      projectService.createProject(name, path)

      res.status(201).json({
        success: true,
        message: `Project '${name}' created successfully`,
      })
    } catch (error) {
      console.error('Error creating project:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to create project: ${errorMessage}` })
    }
  })

  /**
   * GET /api/projects/:name/config
   * Get project configuration with masked sensitive values
   */
  app.get('/api/projects/:name/config', (req: express.Request, res: express.Response) => {
    try {
      const { name } = req.params
      if (!name) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('PROJECT', `GET project config: ${name}`)
      const maskedConfig = projectService.getProjectConfigForClient(name)

      if (!maskedConfig) {
        res.status(404).json({ error: `Project '${name}' not found` })
        return
      }

      res.status(200).json(maskedConfig)
    } catch (error) {
      console.error('Error retrieving project config:', error)
      res.status(500).json({ error: 'Failed to retrieve project configuration' })
    }
  })

  /**
   * PUT /api/projects/:name/config
   * Update project configuration with automatic unmasking of sensitive values
   */
  app.put('/api/projects/:name/config', (req: express.Request, res: express.Response) => {
    try {
      const { name } = req.params
      if (!name) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const incomingConfig = req.body as ProjectLocalConfig

      // Basic validation
      if (!incomingConfig || typeof incomingConfig !== 'object') {
        res.status(422).json({ error: 'Invalid configuration format' })
        return
      }

      if (typeof incomingConfig.version !== 'number') {
        res.status(422).json({ error: 'Configuration must have a version number' })
        return
      }

      if (!incomingConfig.path || typeof incomingConfig.path !== 'string') {
        res.status(422).json({ error: 'Configuration must have a valid path' })
        return
      }

      debugLog('PROJECT', `PUT project config: ${name}`)
      projectService.updateProjectConfigFromClient(name, incomingConfig)

      res.status(200).json({
        success: true,
        message: 'Project configuration updated successfully',
      })
    } catch (error) {
      console.error('Error updating project config:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to update project configuration: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:name
   * Delete a project
   */
  app.delete('/api/projects/:name', (req: express.Request, res: express.Response) => {
    try {
      const { name } = req.params
      if (!name) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('PROJECT', `DELETE project: ${name}`)
      projectService.deleteProject(name)

      res.status(200).json({
        success: true,
        message: `Project '${name}' deleted successfully`,
      })
    } catch (error) {
      console.error('Error deleting project:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to delete project: ${errorMessage}` })
    }
  })
}
