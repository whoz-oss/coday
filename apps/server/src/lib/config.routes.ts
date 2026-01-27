import express from 'express'
import { debugLog } from './log'
import { ConfigServiceRegistry } from '@coday/service'
import { UserConfig } from '@coday/model'

/**
 * Configuration Management REST API Routes
 *
 * This module provides REST endpoints for managing user and project configurations
 * with automatic masking/unmasking of sensitive values (API keys, tokens, etc.)
 *
 * Endpoints:
 * - GET  /api/config/user           - Retrieve user configuration (masked)
 * - PUT  /api/config/user           - Update user configuration (unmasked)
 * - GET  /api/config/project/:name  - Retrieve project configuration (masked)
 * - PUT  /api/config/project/:name  - Update project configuration (unmasked)
 */

/**
 * Register configuration routes on the Express app
 * @param app - Express application instance
 * @param configRegistry - Configuration service registry for accessing user/project services
 * @param getUsernameFn - Function to extract username from request (handles auth/no-auth modes)
 */
export function registerConfigRoutes(
  app: express.Application,
  configRegistry: ConfigServiceRegistry,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/config/user
   * Retrieve user configuration with masked sensitive values
   */
  app.get('/api/config/user', (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('CONFIG', `GET user config for: ${username}`)
      const userService = configRegistry.getUserService(username)

      // Get masked config from service
      const maskedConfig = userService.getConfigForClient()

      res.status(200).json(maskedConfig)
    } catch (error) {
      console.error('Error retrieving user config:', error)
      res.status(500).json({ error: 'Failed to retrieve user configuration' })
    }
  })

  /**
   * PUT /api/config/user
   * Update user configuration with automatic unmasking of sensitive values
   */
  app.put('/api/config/user', (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      const incomingConfig = req.body as UserConfig

      // Basic validation
      if (!incomingConfig || typeof incomingConfig !== 'object') {
        res.status(422).json({ error: 'Invalid configuration format' })
        return
      }

      if (typeof incomingConfig.version !== 'number') {
        res.status(422).json({ error: 'Configuration must have a version number' })
        return
      }

      debugLog('CONFIG', `PUT user config for: ${username}`)
      const userService = configRegistry.getUserService(username)

      // Update config through service (handles unmasking internally)
      userService.updateConfigFromClient(incomingConfig)

      res.status(200).json({ success: true, message: 'User configuration updated successfully' })
    } catch (error) {
      console.error('Error updating user config:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to update user configuration: ${errorMessage}` })
    }
  })
}
