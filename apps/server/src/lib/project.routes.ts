import express from 'express'
import { execFile } from 'child_process'
import * as nodePath from 'path'
import { debugLog } from './log'
import { ProjectService } from '@coday/service'
import { CodayOptions, ProjectLocalConfig } from '@coday/model'
import { getParamAsString } from './route-helpers'
import { ProjectEventManager } from './project-event-manager'
import type { ThreadCodayManager } from './thread-coday-manager'

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
 * @param projectEventManager
 * @param threadService
 * @param getUsernameFn
 */
export function registerProjectRoutes(
  app: express.Application,
  projectService: ProjectService,
  projectEventManager?: ProjectEventManager,
  threadService?: import('@coday/service').ThreadService,
  getUsernameFn?: (req: express.Request) => string,
  threadCodayManager?: ThreadCodayManager,
  codayOptions?: CodayOptions
): void {
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
      const name = getParamAsString(req.params.name)
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
      const name = getParamAsString(req.params.name)
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
      const name = getParamAsString(req.params.name)
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
   * GET /api/threads
   * List all threads across all accessible projects for the current user.
   * Aggregates results from every project in parallel.
   * Each thread already carries a `projectId` field.
   */
  app.get('/api/threads', async (req: express.Request, res: express.Response) => {
    if (!threadService || !getUsernameFn) {
      res.status(503).json({ error: 'Global thread listing not available' })
      return
    }

    try {
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      const projects = projectService.listProjects()

      const results = await Promise.all(
        projects.map(async (project) => {
          try {
            const threads = await threadService!.listThreads(project.name, username)
            // Override projectId with the actual project name used to fetch the threads.
            // The stored projectId in the YAML may differ (e.g. volatile project IDs)
            // and would cause 404s on subsequent operations (delete, stop, star).
            // Enrich with pendingInvite from the in-memory registry (same as per-project endpoint).
            return threads.map((t) => ({
              ...t,
              projectId: project.name,
              pendingInvite: threadCodayManager?.hasPendingInvite(t.id) || undefined,
            }))
          } catch {
            return []
          }
        })
      )

      const allThreads = results.flat()
      res.status(200).json(allThreads)
    } catch (error) {
      console.error('Error listing all threads:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list threads: ${errorMessage}` })
    }
  })

  /**
   * GET /api/projects/:name/git/branches
   * List remote git branches for a project.
   * Returns a sorted array of branch names (refs/remotes/origin/ prefix stripped).
   */
  app.get('/api/projects/:name/git/branches', async (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    const project = projectService.getProject(name)
    if (!project) {
      res.status(404).json({ error: `Project '${name}' not found` })
      return
    }

    const projectPath = project.config.path
    if (!projectPath) {
      res.status(422).json({ error: `Project '${name}' has no configured path` })
      return
    }

    try {
      const branches = await new Promise<string[]>((resolve, reject) => {
        execFile('git', ['branch', '-a', '--format=%(refname:short)'], { cwd: projectPath }, (err, stdout) => {
          if (err) {
            reject(err)
            return
          }
          const lines = stdout
            .split('\n')
            .map((b) => b.trim())
            .filter((b) => b && !b.includes('HEAD'))
            // Normalise remote refs: origin/main → main
            .map((b) => b.replace(/^origin\//, ''))
          resolve([...new Set(lines)].sort())
        })
      })
      res.status(200).json({ branches })
    } catch (error) {
      const msg = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list branches: ${msg}` })
    }
  })

  /**
   * POST /api/projects/:name/missions
   * Create and immediately start a new mission (thread + agent run).
   *
   * Body: {
   *   agentName: string        — agent to invoke (e.g. "Sway")
   *   task: string             — free-text mission description
   *   mode: "local" | "worktree"
   *   branch?: string          — required when mode=worktree
   * }
   *
   * Returns: { threadId: string }
   */
  app.post('/api/projects/:name/missions', async (req: express.Request, res: express.Response) => {
    if (!threadService || !getUsernameFn || !threadCodayManager || !codayOptions) {
      res.status(503).json({ error: 'Mission creation not available' })
      return
    }

    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).json({ error: 'Project name is required' })
      return
    }

    const { agentName, task, mode, branch, issueNumber, branchType } = req.body as {
      agentName?: string
      task?: string
      mode?: 'local' | 'worktree'
      branch?: string
      issueNumber?: string
      branchType?: string
    }

    if (!agentName || !task || !mode) {
      res.status(400).json({ error: 'agentName, task and mode are required' })
      return
    }

    if (mode === 'worktree' && !branch) {
      res.status(400).json({ error: 'branch is required for worktree mode' })
      return
    }

    const username = getUsernameFn(req)
    if (!username) {
      res.status(401).json({ error: 'Authentication required' })
      return
    }

    try {
      let targetProject = name
      let worktreeProjectName: string | undefined

      if (mode === 'worktree' && branch) {
        // Build new branch name: feature/username/WZ-585 or feature/username/DT-123
        const issueNum = issueNumber ?? task.match(/([A-Z]+-\d+)/)?.[1] ?? Date.now().toString()
        const type = branchType ?? 'feature'
        const newBranchName = `${type}/${username}/${issueNum}`

        // Worktree project name derived from new branch
        const branchSlug = newBranchName
          .replace(/[^a-zA-Z0-9]/g, '-')
          .replace(/-+/g, '-')
          .replace(/^-|-$/g, '')
        worktreeProjectName = `coday__${branchSlug}`

        // Get source project path
        const sourceProject = projectService.getProject(name)
        if (!sourceProject?.config?.path) {
          res.status(422).json({ error: `Project '${name}' has no configured path` })
          return
        }
        const sourcePath = sourceProject.config.path
        const worktreePath = nodePath.resolve(sourcePath, '..', worktreeProjectName)

        // Create a new branch from the source branch and checkout it in the worktree
        await new Promise<void>((resolve, reject) => {
          execFile(
            'git',
            ['worktree', 'add', '-b', newBranchName, worktreePath, branch],
            { cwd: sourcePath },
            (err) => {
              if (err) reject(new Error(`git worktree add failed: ${err.message}`))
              else resolve()
            }
          )
        })

        // Register the worktree as a Coday project (copies parent config + symlinks)
        await projectService.registerWorktreeProject(worktreeProjectName, worktreePath, name)
        targetProject = worktreeProjectName
      }

      // Build the command for the agent
      const command = `@${agentName} ${task}`

      // Create the thread on the target project
      const thread = await threadService.createThread(targetProject, username)
      const threadId = thread.id

      // Persist worktreeProject metadata on the thread if applicable
      if (worktreeProjectName) {
        const aiThread = await threadService.getThread(targetProject, threadId)
        if (aiThread) {
          aiThread.worktreeProject = worktreeProjectName
          await threadService.saveThread(targetProject, aiThread)
        }
      }

      // Start an interactive Coday instance
      const instanceOptions: CodayOptions = {
        ...codayOptions,
        oneshot: false,
        project: targetProject,
        thread: threadId,
        prompts: [command],
      }

      const instance = threadCodayManager.createWithoutConnection(threadId, targetProject, username, instanceOptions)
      instance.startCoday()

      res.status(201).json({ threadId, projectId: targetProject })
    } catch (error) {
      console.error('Error creating mission:', error)
      const msg = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to create mission: ${msg}` })
    }
  })

  /**
   * DELETE /api/projects/:name/missions/:threadId
   * Close a worktree mission: stop the agent, delete the thread,
   * remove the Coday project registration, and remove the git worktree from disk.
   */
  app.delete('/api/projects/:name/missions/:threadId', async (req: express.Request, res: express.Response) => {
    if (!threadService || !getUsernameFn) {
      res.status(503).json({ error: 'Mission management not available' })
      return
    }

    const name = getParamAsString(req.params.name)
    const threadId = getParamAsString(req.params.threadId)
    if (!name || !threadId) {
      res.status(400).json({ error: 'Project name and thread ID are required' })
      return
    }

    try {
      const thread = await threadService.getThread(name, threadId)
      if (!thread) {
        res.status(404).json({ error: `Thread '${threadId}' not found` })
        return
      }

      const worktreeProjectName = thread.worktreeProject

      // Stop running instance if any
      threadCodayManager?.stop(threadId)

      // Delete the thread
      await threadService.deleteThread(name, threadId)

      if (worktreeProjectName) {
        const worktreeProject = projectService.getProject(worktreeProjectName)
        const worktreePath = worktreeProject?.config?.path

        // Remove git worktree from disk and delete the associated branch
        if (worktreePath) {
          const sourceProject = projectService.getProject(name)
          const sourcePath = sourceProject?.config?.path
          if (sourcePath) {
            // Detect the branch name checked out in the worktree before removing it
            const branchName = await new Promise<string | null>((resolve) => {
              execFile('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: worktreePath }, (err, stdout) =>
                resolve(err ? null : stdout.trim())
              )
            })

            await new Promise<void>((resolve) => {
              execFile('git', ['worktree', 'remove', '--force', worktreePath], { cwd: sourcePath }, () => resolve())
            })
            await new Promise<void>((resolve) => {
              execFile('git', ['worktree', 'prune'], { cwd: sourcePath }, () => resolve())
            })

            // Delete the local branch so recreating a mission with the same ticket works
            if (branchName && branchName !== 'HEAD') {
              await new Promise<void>((resolve) => {
                execFile('git', ['branch', '-D', branchName], { cwd: sourcePath }, () => resolve())
              })
            }
          }
        }

        // Unregister the Coday project (migrates threads to parent + removes config dir)
        await projectService.unregisterWorktreeProject(worktreeProjectName)
      }

      res.status(200).json({ success: true })
    } catch (error) {
      console.error('Error closing mission:', error)
      const msg = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to close mission: ${msg}` })
    }
  })

  /**
   * GET /api/projects/:name/event-stream
   * Project-level SSE endpoint — broadcasts ThreadUpdateEvent for any thread in the project.
   * Used by Mission Control to auto-refresh the thread list without a per-thread SSE connection.
   */
  app.get('/api/projects/:name/event-stream', (req: express.Request, res: express.Response) => {
    const name = getParamAsString(req.params.name)
    if (!name) {
      res.status(400).send('Project name is required')
      return
    }

    if (!projectEventManager) {
      res.status(503).send('Project event stream not available')
      return
    }

    debugLog('PROJECT_SSE', `New SSE connection for project ${name}`)

    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')

    projectEventManager.addConnection(name, res)

    req.on('close', () => {
      debugLog('PROJECT_SSE', `Client disconnected from project ${name}`)
      projectEventManager.removeConnection(name, res)
    })
  })

  /**
   * DELETE /api/projects/:name
   * Delete a project
   */
  app.delete('/api/projects/:name', async (req: express.Request, res: express.Response) => {
    try {
      const name = getParamAsString(req.params.name)
      if (!name) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const removeGitWorktree = req.query['removeGitWorktree'] === 'true'
      debugLog('PROJECT', `DELETE project: ${name}${removeGitWorktree ? ' (with git worktree removal)' : ''}`)
      await projectService.deleteProject(name, { removeGitWorktree })

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
