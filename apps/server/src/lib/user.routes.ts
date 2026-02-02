import express from 'express'
import { debugLog } from './log'

/**
 * User Information REST API Routes
 *
 * This module provides endpoints for retrieving current user information.
 *
 * Endpoints:
 * - GET /api/user/me - Get current authenticated user info
 *
 * Authentication:
 * - Username extracted from x-forwarded-email header (set by auth proxy)
 * - In no-auth mode, uses local system username
 */

/**
 * Register user information routes on the Express app
 * @param app - Express application instance
 * @param getUsernameFn - Function to extract username from request
 */
export function registerUserRoutes(app: express.Application, getUsernameFn: (req: express.Request) => string): void {
  /**
   * GET /api/user/me
   * Get current authenticated user information
   */
  app.get('/api/user/me', (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      debugLog('USER', `GET current user: ${username}`)

      res.status(200).json({
        username,
      })
    } catch (error) {
      console.error('Error retrieving user info:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to retrieve user info: ${errorMessage}` })
    }
  })
}
