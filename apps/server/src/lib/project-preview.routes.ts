import express from 'express'
import { ProjectService } from '@coday/service'
import { debugLog } from './log'
import { getParamAsString } from './route-helpers'
import { previewManager } from './preview-manager'

/**
 * Preview Server REST API Routes
 *
 * Each preview entry runs in its own tmux session, so multiple entries
 * can run simultaneously within the same project.
 *
 * Endpoints:
 * - GET  /api/projects/:name/preview/entries                -> list preview entries
 * - POST /api/projects/:name/preview/:entry/start           -> start entry
 * - POST /api/projects/:name/preview/:entry/stop            -> stop entry
 * - GET  /api/projects/:name/preview/:entry/status          -> entry status
 * - GET  /api/projects/:name/preview/:entry/logs            -> entry logs
 */
export function registerProjectPreviewRoutes(app: express.Application, projectService: ProjectService): void {
  function resolveProject(name: string, res: express.Response) {
    const project = projectService.getProject(name)
    if (!project) {
      res.status(404).json({ error: `Project '${name}' not found` })
      return null
    }
    return project
  }

  function resolveEntry(
    project: { config: { preview?: { name: string; command: string }[] } },
    entryName: string,
    res: express.Response
  ) {
    const entries = project.config.preview ?? []
    const entry = entries.find((e) => e.name === entryName)
    if (!entry) {
      res.status(404).json({ error: `Preview entry '${entryName}' not found` })
      return null
    }
    return entry
  }

  function handleError(res: express.Response, action: string, error: unknown) {
    console.error(`Error ${action} preview:`, error)
    const message = error instanceof Error ? error.message : 'Unknown error'
    res.status(500).json({ error: `Failed to ${action} preview: ${message}` })
  }

  // List available entries
  app.get('/api/projects/:name/preview/entries', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }
    const project = resolveProject(name, res)
    if (!project) return
    const entries = project.config.preview ?? []
    res.json({ entries })
  })

  // Start an entry
  app.post('/api/projects/:name/preview/:entry/start', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    const entryName = getParamAsString(req.params.entry)
    if (!name || !entryName) {
      res.status(400).json({ error: 'Project name and entry name are required' })
      return
    }
    debugLog('PREVIEW', `POST start preview '${entryName}' for project: ${name}`)
    const project = resolveProject(name, res)
    if (!project) return
    const entry = resolveEntry(project, entryName, res)
    if (!entry) return

    try {
      const state = await previewManager.start(project.config.path, entryName, entry.command)
      res.json({ status: state.status })
    } catch (error) {
      handleError(res, 'start', error)
    }
  })

  // Stop an entry
  app.post('/api/projects/:name/preview/:entry/stop', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    const entryName = getParamAsString(req.params.entry)
    if (!name || !entryName) {
      res.status(400).json({ error: 'Project name and entry name are required' })
      return
    }
    debugLog('PREVIEW', `POST stop preview '${entryName}' for project: ${name}`)
    const project = resolveProject(name, res)
    if (!project) return

    try {
      const state = await previewManager.stop(project.config.path, entryName)
      res.json({ status: state.status })
    } catch (error) {
      handleError(res, 'stop', error)
    }
  })

  // Get entry status
  app.get('/api/projects/:name/preview/:entry/status', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    const entryName = getParamAsString(req.params.entry)
    if (!name || !entryName) {
      res.status(400).json({ error: 'Project name and entry name are required' })
      return
    }
    debugLog('PREVIEW', `GET preview status for '${entryName}' in project: ${name}`)
    const project = resolveProject(name, res)
    if (!project) return

    try {
      const state = await previewManager.getStatus(project.config.path, entryName)
      res.json({ status: state.status })
    } catch (error) {
      handleError(res, 'get status for', error)
    }
  })

  // Get entry logs
  app.get('/api/projects/:name/preview/:entry/logs', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    const entryName = getParamAsString(req.params.entry)
    if (!name || !entryName) {
      res.status(400).json({ error: 'Project name and entry name are required' })
      return
    }
    debugLog('PREVIEW', `GET preview logs for '${entryName}' in project: ${name}`)
    const project = resolveProject(name, res)
    if (!project) return

    try {
      const logs = await previewManager.getLogs(project.config.path, entryName)
      res.json({ logs })
    } catch (error) {
      handleError(res, 'get logs for', error)
    }
  })
}
