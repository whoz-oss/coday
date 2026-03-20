import express from 'express'
import { ProjectService } from '@coday/service'
import { debugLog } from './log'
import { getParamAsString } from './route-helpers'
import { previewManager } from './preview-manager'

/**
 * Preview Server REST API Routes
 *
 * Endpoints:
 * - POST /api/projects/:name/preview/start  -> start preview, returns { status, port, url }
 * - POST /api/projects/:name/preview/stop   -> stop preview,  returns { status }
 * - GET  /api/projects/:name/preview/status -> current state, returns { status, port?, url? }
 * - GET  /api/projects/:name/preview/logs   -> recent output, returns { logs: string }
 *
 * The preview command and optional display host are read from project.config.preview.
 * Port computation is handled by PreviewManager based on the project root directory name.
 */
export function registerProjectPreviewRoutes(app: express.Application, projectService: ProjectService): void {
  // ── helpers ────────────────────────────────────────────────────────────────

  /** Resolve project or respond 404. Returns null when it already responded. */
  function resolveProject(name: string, res: express.Response) {
    const project = projectService.getProject(name)
    if (!project) {
      res.status(404).json({ error: `Project '${name}' not found` })
      return null
    }
    return project
  }

  /** Centralised error handler for all preview routes. */
  function handleError(res: express.Response, action: string, error: unknown) {
    console.error(`Error ${action} preview:`, error)
    const message = error instanceof Error ? error.message : 'Unknown error'
    res.status(500).json({ error: `Failed to ${action} preview: ${message}` })
  }

  // ── routes ─────────────────────────────────────────────────────────────────

  app.post('/api/projects/:name/preview/start', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }
    debugLog('PREVIEW', `POST start preview for project: ${name}`)
    const project = resolveProject(name, res)
    if (!project) return

    const previewConfig = project.config.preview
    if (!previewConfig?.command) {
      res.status(400).json({ error: `Project '${name}' has no preview.command configured` })
      return
    }
    try {
      const state = await previewManager.start(name, project.config.path, previewConfig.command)
      res.json({ status: state.status, port: state.port, url: state.url })
    } catch (error) {
      handleError(res, 'start', error)
    }
  })

  app.post('/api/projects/:name/preview/stop', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }
    debugLog('PREVIEW', `POST stop preview for project: ${name}`)
    try {
      const state = await previewManager.stop(name)
      res.json({ status: state.status })
    } catch (error) {
      handleError(res, 'stop', error)
    }
  })

  app.get('/api/projects/:name/preview/status', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }
    debugLog('PREVIEW', `GET preview status for project: ${name}`)
    try {
      const project = projectService.getProject(name)
      const state = await previewManager.getStatus(name, project?.config.path)
      res.json({ status: state.status, port: state.port, url: state.url })
    } catch (error) {
      handleError(res, 'get status for', error)
    }
  })

  app.get('/api/projects/:name/preview/logs', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }
    debugLog('PREVIEW', `GET preview logs for project: ${name}`)
    try {
      const project = projectService.getProject(name)
      const logs = await previewManager.getLogs(name, project?.config.path)
      res.json({ logs })
    } catch (error) {
      handleError(res, 'get logs for', error)
    }
  })
}
