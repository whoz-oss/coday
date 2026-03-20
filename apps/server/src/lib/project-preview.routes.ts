import express from 'express'
import { ConfigServiceRegistry, ProjectService } from '@coday/service'
import { debugLog } from './log'
import { getParamAsString } from './route-helpers'
import { previewManager } from './preview-manager'

/**
 * Preview Server REST API Routes
 *
 * Endpoints:
 * - POST /api/projects/:name/preview/start  → start preview, returns { url, port, status }
 * - POST /api/projects/:name/preview/stop   → stop preview, returns { status }
 * - GET  /api/projects/:name/preview/status → returns { status, url?, port? }
 * - GET  /api/projects/:name/preview/logs   → returns { logs: string }
 */
export function registerProjectPreviewRoutes(
  app: express.Application,
  projectService: ProjectService,
  _configRegistry: ConfigServiceRegistry,
  _getUsernameFn: (req: express.Request) => string
): void {
  /**
   * POST /api/projects/:name/preview/start
   * Start the preview server for the project.
   */
  app.post('/api/projects/:name/preview/start', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    debugLog('PREVIEW', `POST start preview for project: ${name}`)

    const project = projectService.getProject(name)
    if (!project) {
      res.status(404).json({ error: `Project '${name}' not found` })
      return
    }

    const previewConfig = project.config.preview
    if (!previewConfig?.command) {
      res.status(400).json({ error: `Project '${name}' has no preview.command configured` })
      return
    }

    try {
      // User-level previewHost overrides project-level host
      const host = previewConfig.host ?? '0.0.0.0'

      const state = await previewManager.start(name, project.config.path, previewConfig.command, host)
      res
        .status(200)
        .json({
          status: state.status,
          ...(state.port !== undefined ? { port: state.port } : {}),
          ...(state.url !== undefined ? { url: state.url } : {}),
        })
    } catch (error) {
      console.error('Error starting preview:', error)
      const message = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to start preview: ${message}` })
    }
  })

  /**
   * POST /api/projects/:name/preview/stop
   * Stop the preview server for the project.
   */
  app.post('/api/projects/:name/preview/stop', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    debugLog('PREVIEW', `POST stop preview for project: ${name}`)

    try {
      const state = await previewManager.stop(name)
      res.status(200).json({ status: state.status })
    } catch (error) {
      console.error('Error stopping preview:', error)
      const message = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to stop preview: ${message}` })
    }
  })

  /**
   * GET /api/projects/:name/preview/status
   * Get the current status of the preview server.
   */
  app.get('/api/projects/:name/preview/status', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    debugLog('PREVIEW', `GET preview status for project: ${name}`)

    try {
      const state = await previewManager.getStatus(name)
      res.status(200).json({
        status: state.status,
        ...(state.port !== undefined ? { port: state.port } : {}),
        ...(state.url !== undefined ? { url: state.url } : {}),
      })
    } catch (error) {
      console.error('Error getting preview status:', error)
      const message = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to get preview status: ${message}` })
    }
  })

  /**
   * GET /api/projects/:name/preview/logs
   * Get recent logs from the preview tmux session.
   */
  app.get('/api/projects/:name/preview/logs', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    debugLog('PREVIEW', `GET preview logs for project: ${name}`)

    try {
      const logs = await previewManager.getLogs(name)
      res.status(200).json({ logs })
    } catch (error) {
      console.error('Error getting preview logs:', error)
      const message = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to get preview logs: ${message}` })
    }
  })
}
