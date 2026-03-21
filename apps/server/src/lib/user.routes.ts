import express from 'express'
import fs from 'fs'
import path from 'path'
import { debugLog } from './log'
import { readYamlFile } from '@coday/utils'

/**
 * User Information REST API Routes
 *
 * This module provides endpoints for retrieving current user information.
 *
 * Endpoints:
 * - GET /api/user/me - Get current authenticated user info
 * - GET /api/users - List all known usernames
 *
 * Authentication:
 * - Username extracted from x-forwarded-email header (set by auth proxy)
 * - In no-auth mode, uses local system username
 */

/**
 * Register user information routes on the Express app
 * @param app - Express application instance
 * @param getUsernameFn - Function to extract username from request
 * @param configDir - Path to the Coday config directory (e.g. ~/.coday)
 */
export function registerUserRoutes(
  app: express.Application,
  getUsernameFn: (req: express.Request) => string,
  configDir: string
): void {
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

  /**
   * GET /api/users
   * List all known usernames by scanning the users directory
   */
  app.get('/api/users', (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      const usersDir = path.join(configDir, 'users')

      if (!fs.existsSync(usersDir)) {
        debugLog('USER', 'GET all users: users directory does not exist, returning empty list')
        res.status(200).json([])
        return
      }

      const entries = fs.readdirSync(usersDir, { withFileTypes: true })
      const usernames = entries
        .filter((e) => e.isDirectory())
        .flatMap((e) => {
          const yamlPath = path.join(usersDir, e.name, 'user.yaml')
          if (!fs.existsSync(yamlPath)) return []
          const config = readYamlFile(yamlPath) as { username?: string } | null
          // Use the stored raw username (email) if available, otherwise fall back to directory name
          const rawUsername = config?.username ?? e.name
          return [{ username: rawUsername }]
        })
        // Exclude the current user from the list
        .filter((u) => u.username !== username)

      debugLog('USER', `GET all users: found ${usernames.length} user(s)`)

      res.status(200).json(usernames)
    } catch (error) {
      console.error('Error listing users:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list users: ${errorMessage}` })
    }
  })
}
