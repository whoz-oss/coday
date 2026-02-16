import type express from 'express'
import { SchedulerService, IntervalSchedule } from '@coday/service'
import { getParamAsString } from './route-helpers'
import { debugLog } from './log'

/**
 * Scheduler Management REST API Routes
 *
 * Architecture:
 * - CRUD operations are scoped to projects: /api/projects/:projectName/schedulers
 * - Access control: owner OR CODAY_ADMIN can access
 * - Each scheduler is owned by createdBy user (who executes the prompts)
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/schedulers              - List schedulers for project
 * - GET    /api/projects/:projectName/schedulers/:id          - Get specific scheduler
 * - POST   /api/projects/:projectName/schedulers              - Create new scheduler
 * - PUT    /api/projects/:projectName/schedulers/:id          - Update scheduler
 * - DELETE /api/projects/:projectName/schedulers/:id          - Delete scheduler
 * - POST   /api/projects/:projectName/schedulers/:id/enable   - Enable scheduler
 * - POST   /api/projects/:projectName/schedulers/:id/disable  - Disable scheduler
 * - POST   /api/projects/:projectName/schedulers/:id/run-now  - Execute scheduler now
 */

/**
 * Register scheduler-related REST API routes
 */
export function registerSchedulerRoutes(
  app: express.Application,
  schedulerService: SchedulerService,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/schedulers
   * List all schedulers for a project with access control
   */
  app.get('/api/projects/:projectName/schedulers', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `GET schedulers for project: ${projectName}, user: ${username}`)
      const schedulers = await schedulerService.listSchedulers(projectName, username)
      res.json(schedulers)
    } catch (error) {
      console.error('Error listing schedulers:', error)
      res.status(500).json({ error: 'Failed to list schedulers' })
    }
  })

  /**
   * GET /api/projects/:projectName/schedulers/:id
   * Get a specific scheduler with ownership verification
   */
  app.get('/api/projects/:projectName/schedulers/:id', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `GET scheduler: ${id} in project: ${projectName}, user: ${username}`)
      const scheduler = await schedulerService.getScheduler(projectName, id, username)

      if (!scheduler) {
        res.status(404).json({ error: 'Scheduler not found or access denied' })
        return
      }

      res.json(scheduler)
    } catch (error) {
      console.error('Error getting scheduler:', error)
      res.status(500).json({ error: 'Failed to get scheduler' })
    }
  })

  /**
   * POST /api/projects/:projectName/schedulers
   * Create a new scheduler
   */
  app.post('/api/projects/:projectName/schedulers', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      const { name, promptId, schedule, parameters, enabled } = req.body as {
        name: string
        promptId: string
        schedule: IntervalSchedule
        parameters?: Record<string, unknown>
        enabled?: boolean
      }

      // Validation
      if (!name || typeof name !== 'string') {
        res.status(400).json({ error: 'Name is required and must be a string' })
        return
      }

      if (!promptId || typeof promptId !== 'string') {
        res.status(400).json({ error: 'Prompt ID is required and must be a string' })
        return
      }

      if (!schedule || typeof schedule !== 'object') {
        res.status(400).json({ error: 'Schedule is required and must be an object' })
        return
      }

      // Validate interval schedule
      const validation = schedulerService.validateSchedule(schedule)
      if (!validation.valid) {
        res.status(400).json({ error: validation.error })
        return
      }

      if (parameters !== undefined && typeof parameters !== 'object') {
        res.status(400).json({ error: 'Parameters must be an object' })
        return
      }

      if (enabled !== undefined && typeof enabled !== 'boolean') {
        res.status(400).json({ error: 'Enabled must be a boolean' })
        return
      }

      debugLog('SCHEDULER_API', `POST new scheduler: ${name} in project: ${projectName}, user: ${username}`)

      const scheduler = await schedulerService.createScheduler(
        projectName,
        { name, promptId, schedule, parameters, enabled },
        username
      )

      res.status(201).json(scheduler)
    } catch (error) {
      console.error('Error creating scheduler:', error)
      const message = error instanceof Error ? error.message : 'Failed to create scheduler'
      res.status(500).json({ error: message })
    }
  })

  /**
   * PUT /api/projects/:projectName/schedulers/:id
   * Update a scheduler with ownership verification
   */
  app.put('/api/projects/:projectName/schedulers/:id', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      const { name, enabled, promptId, schedule, parameters } = req.body

      // Validation
      if (name !== undefined && typeof name !== 'string') {
        res.status(400).json({ error: 'Name must be a string' })
        return
      }

      if (enabled !== undefined && typeof enabled !== 'boolean') {
        res.status(400).json({ error: 'Enabled must be a boolean' })
        return
      }

      if (promptId !== undefined && typeof promptId !== 'string') {
        res.status(400).json({ error: 'Prompt ID must be a string' })
        return
      }

      if (schedule !== undefined) {
        if (typeof schedule !== 'object') {
          res.status(400).json({ error: 'Schedule must be an object' })
          return
        }

        // Validate interval schedule
        const validation = schedulerService.validateSchedule(schedule)
        if (!validation.valid) {
          res.status(400).json({ error: validation.error })
          return
        }
      }

      if (parameters !== undefined && typeof parameters !== 'object') {
        res.status(400).json({ error: 'Parameters must be an object' })
        return
      }

      debugLog('SCHEDULER_API', `PUT scheduler: ${id} in project: ${projectName}, user: ${username}`)

      const scheduler = await schedulerService.updateScheduler(
        projectName,
        id,
        {
          name,
          enabled,
          promptId,
          schedule,
          parameters,
        },
        username
      )

      res.json(scheduler)
    } catch (error) {
      console.error('Error updating scheduler:', error)
      const message = error instanceof Error ? error.message : 'Failed to update scheduler'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * DELETE /api/projects/:projectName/schedulers/:id
   * Delete a scheduler with ownership verification
   */
  app.delete('/api/projects/:projectName/schedulers/:id', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `DELETE scheduler: ${id} in project: ${projectName}, user: ${username}`)

      const deleted = await schedulerService.deleteScheduler(projectName, id, username)

      if (!deleted) {
        res.status(404).json({ error: 'Scheduler not found or access denied' })
        return
      }

      res.json({ success: true, message: 'Scheduler deleted' })
    } catch (error) {
      console.error('Error deleting scheduler:', error)
      res.status(500).json({ error: 'Failed to delete scheduler' })
    }
  })

  /**
   * POST /api/projects/:projectName/schedulers/:id/enable
   * Enable a scheduler with ownership verification
   */
  app.post('/api/projects/:projectName/schedulers/:id/enable', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `POST enable scheduler: ${id} in project: ${projectName}, user: ${username}`)

      const scheduler = await schedulerService.enableScheduler(projectName, id, username)
      res.json(scheduler)
    } catch (error) {
      console.error('Error enabling scheduler:', error)
      const message = error instanceof Error ? error.message : 'Failed to enable scheduler'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * POST /api/projects/:projectName/schedulers/:id/disable
   * Disable a scheduler with ownership verification
   */
  app.post('/api/projects/:projectName/schedulers/:id/disable', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `POST disable scheduler: ${id} in project: ${projectName}, user: ${username}`)

      const scheduler = await schedulerService.disableScheduler(projectName, id, username)
      res.json(scheduler)
    } catch (error) {
      console.error('Error disabling scheduler:', error)
      const message = error instanceof Error ? error.message : 'Failed to disable scheduler'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * POST /api/projects/:projectName/schedulers/:id/run-now
   * Manually execute a scheduler now (for testing) with ownership verification
   */
  app.post('/api/projects/:projectName/schedulers/:id/run-now', async (req, res) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Scheduler ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('SCHEDULER_API', `POST run-now scheduler: ${id} in project: ${projectName}, user: ${username}`)

      const threadId = await schedulerService.runSchedulerNow(projectName, id, username)

      res.json({
        success: true,
        message: 'Scheduler executed',
        threadId,
      })
    } catch (error) {
      console.error('Error running scheduler:', error)
      const message = error instanceof Error ? error.message : 'Failed to run scheduler'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })
}
