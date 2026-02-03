import type express from 'express'
import { TriggerService } from '@coday/service'

/**
 * Register trigger-related REST API routes
 */
export function registerTriggerRoutes(
  app: express.Application,
  triggerService: TriggerService,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/triggers
   * List all triggers for a project owned by the current user
   */
  app.get('/api/projects/:projectName/triggers', async (req, res) => {
    try {
      const { projectName } = req.params
      const username = getUsernameFn(req)
      const triggers = await triggerService.listTriggers(projectName, username)
      res.json(triggers)
    } catch (error) {
      console.error('Error listing triggers:', error)
      res.status(500).json({ error: 'Failed to list triggers' })
    }
  })

  /**
   * GET /api/projects/:projectName/triggers/:id
   * Get a specific trigger with ownership verification
   */
  app.get('/api/projects/:projectName/triggers/:id', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const trigger = await triggerService.getTrigger(projectName, id, username)

      if (!trigger) {
        res.status(404).json({ error: 'Trigger not found or access denied' })
        return
      }

      res.json(trigger)
    } catch (error) {
      console.error('Error getting trigger:', error)
      res.status(500).json({ error: 'Failed to get trigger' })
    }
  })

  /**
   * POST /api/projects/:projectName/triggers
   * Create a new trigger
   */
  app.post('/api/projects/:projectName/triggers', async (req, res) => {
    try {
      const { projectName } = req.params
      const username = getUsernameFn(req)
      const { name, webhookUuid, schedule, parameters, enabled } = req.body as {
        name: string
        webhookUuid: string
        schedule: any
        parameters?: Record<string, unknown>
        enabled?: boolean
      }

      // Validation
      if (!name || typeof name !== 'string') {
        res.status(400).json({ error: 'Name is required and must be a string' })
        return
      }

      if (!webhookUuid || typeof webhookUuid !== 'string') {
        res.status(400).json({ error: 'Webhook UUID is required and must be a string' })
        return
      }

      if (!schedule || typeof schedule !== 'object') {
        res.status(400).json({ error: 'Schedule is required and must be an object' })
        return
      }

      // Validate interval schedule
      const validation = triggerService.validateSchedule(schedule)
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

      const trigger = await triggerService.createTrigger(
        projectName,
        { name, webhookUuid, schedule, parameters, enabled },
        username
      )

      res.status(201).json(trigger)
    } catch (error) {
      console.error('Error creating trigger:', error)
      const message = error instanceof Error ? error.message : 'Failed to create trigger'
      res.status(500).json({ error: message })
    }
  })

  /**
   * PUT /api/projects/:projectName/triggers/:id
   * Update a trigger with ownership verification
   */
  app.put('/api/projects/:projectName/triggers/:id', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const { name, enabled, webhookUuid, schedule, parameters } = req.body

      // Validation
      if (name !== undefined && typeof name !== 'string') {
        res.status(400).json({ error: 'Name must be a string' })
        return
      }

      if (enabled !== undefined && typeof enabled !== 'boolean') {
        res.status(400).json({ error: 'Enabled must be a boolean' })
        return
      }

      if (webhookUuid !== undefined && typeof webhookUuid !== 'string') {
        res.status(400).json({ error: 'Webhook UUID must be a string' })
        return
      }

      if (schedule !== undefined) {
        if (typeof schedule !== 'object') {
          res.status(400).json({ error: 'Schedule must be an object' })
          return
        }

        // Validate interval schedule
        const validation = triggerService.validateSchedule(schedule)
        if (!validation.valid) {
          res.status(400).json({ error: validation.error })
          return
        }
      }

      if (parameters !== undefined && typeof parameters !== 'object') {
        res.status(400).json({ error: 'Parameters must be an object' })
        return
      }

      const trigger = await triggerService.updateTrigger(
        projectName,
        id,
        {
          name,
          enabled,
          webhookUuid,
          schedule,
          parameters,
        },
        username
      )

      res.json(trigger)
    } catch (error) {
      console.error('Error updating trigger:', error)
      const message = error instanceof Error ? error.message : 'Failed to update trigger'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * DELETE /api/projects/:projectName/triggers/:id
   * Delete a trigger with ownership verification
   */
  app.delete('/api/projects/:projectName/triggers/:id', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const deleted = await triggerService.deleteTrigger(projectName, id, username)

      if (!deleted) {
        res.status(404).json({ error: 'Trigger not found or access denied' })
        return
      }

      res.json({ success: true, message: 'Trigger deleted' })
    } catch (error) {
      console.error('Error deleting trigger:', error)
      res.status(500).json({ error: 'Failed to delete trigger' })
    }
  })

  /**
   * POST /api/projects/:projectName/triggers/:id/enable
   * Enable a trigger with ownership verification
   */
  app.post('/api/projects/:projectName/triggers/:id/enable', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const trigger = await triggerService.enableTrigger(projectName, id, username)
      res.json(trigger)
    } catch (error) {
      console.error('Error enabling trigger:', error)
      const message = error instanceof Error ? error.message : 'Failed to enable trigger'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * POST /api/projects/:projectName/triggers/:id/disable
   * Disable a trigger with ownership verification
   */
  app.post('/api/projects/:projectName/triggers/:id/disable', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const trigger = await triggerService.disableTrigger(projectName, id, username)
      res.json(trigger)
    } catch (error) {
      console.error('Error disabling trigger:', error)
      const message = error instanceof Error ? error.message : 'Failed to disable trigger'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })

  /**
   * POST /api/projects/:projectName/triggers/:id/run-now
   * Manually execute a trigger now (for testing) with ownership verification
   */
  app.post('/api/projects/:projectName/triggers/:id/run-now', async (req, res) => {
    try {
      const { projectName, id } = req.params
      const username = getUsernameFn(req)
      const threadId = await triggerService.runTriggerNow(projectName, id, username)

      res.json({
        success: true,
        message: 'Trigger executed',
        threadId,
      })
    } catch (error) {
      console.error('Error running trigger:', error)
      const message = error instanceof Error ? error.message : 'Failed to run trigger'

      if (message.includes('not found') || message.includes('access denied')) {
        res.status(404).json({ error: message })
      } else {
        res.status(500).json({ error: message })
      }
    }
  })
}
