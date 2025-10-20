import express from 'express'
import { debugLog } from './log'
import { ThreadService2 } from './services/thread.service2'
import { ThreadCodayManager } from './thread-coday-manager'
import { ImageContent } from '@coday/coday-events'
import { processImageBuffer } from '../../../libs/function/image-processor'

/**
 * Thread Management REST API Routes
 *
 * This module provides REST endpoints for managing threads within projects.
 * Threads are hierarchically organized under projects, reflecting their
 * natural relationship.
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/threads                     - List threads
 * - POST   /api/projects/:projectName/threads                     - Create thread
 * - GET    /api/projects/:projectName/threads/:threadId           - Get thread
 * - PUT    /api/projects/:projectName/threads/:threadId           - Update thread
 * - DELETE /api/projects/:projectName/threads/:threadId           - Delete thread
 * - POST   /api/projects/:projectName/threads/:threadId/stop      - Stop thread execution
 * - POST   /api/projects/:projectName/threads/:threadId/upload    - Upload file to thread
 *
 * Authentication:
 * - Username extracted from x-forwarded-email header (set by auth proxy)
 * - Users can only access their own threads (enforced by service layer)
 */

/**
 * Register thread management routes on the Express app
 * @param app - Express application instance
 * @param threadService - ThreadService2 instance for thread operations
 * @param threadCodayManager - ThreadCodayManager instance for runtime operations
 * @param getUsernameFn - Function to extract username from request
 */
export function registerThreadRoutes(
  app: express.Application,
  threadService: ThreadService2,
  threadCodayManager: ThreadCodayManager,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/threads
   * List all threads for a project (filtered by current user)
   */
  app.get('/api/projects/:projectName/threads', async (req: express.Request, res: express.Response) => {
    try {
      const { projectName } = req.params
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      debugLog('THREAD', `GET threads for project: ${projectName}, user: ${username}`)
      const threads = await threadService.listThreads(projectName, username)

      res.status(200).json(threads)
    } catch (error) {
      console.error('Error listing threads:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to list threads: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/threads
   * Create a new thread in a project
   *
   * Body: { name?: string }
   */
  app.post('/api/projects/:projectName/threads', async (req: express.Request, res: express.Response) => {
    try {
      const { projectName } = req.params
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      const { name } = req.body

      debugLog(
        'THREAD',
        `POST create thread in project: ${projectName}, user: ${username}, name: ${name || 'untitled'}`
      )
      const thread = await threadService.createThread(projectName, username, name)

      res.status(201).json({
        success: true,
        thread: {
          id: thread.id,
          name: thread.name,
          projectId: thread.projectId,
          username: thread.username,
          createdDate: thread.createdDate,
          modifiedDate: thread.modifiedDate,
        },
      })
    } catch (error) {
      console.error('Error creating thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to create thread: ${errorMessage}` })
    }
  })

  /**
   * GET /api/projects/:projectName/threads/:threadId
   * Get a specific thread with full details
   */
  app.get('/api/projects/:projectName/threads/:threadId', async (req: express.Request, res: express.Response) => {
    try {
      const { projectName, threadId } = req.params
      if (!projectName || !threadId) {
        res.status(400).json({ error: 'Project name and thread ID are required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      debugLog('THREAD', `GET thread: ${threadId} from project: ${projectName}`)
      const thread = await threadService.getThread(projectName, threadId)

      if (!thread) {
        res.status(404).json({ error: `Thread '${threadId}' not found in project '${projectName}'` })
        return
      }

      // Verify user owns this thread
      if (thread.username !== username) {
        res.status(403).json({ error: 'Access denied: thread belongs to another user' })
        return
      }

      res.status(200).json({
        id: thread.id,
        name: thread.name,
        projectId: thread.projectId,
        username: thread.username,
        summary: thread.summary,
        createdDate: thread.createdDate,
        modifiedDate: thread.modifiedDate,
        price: thread.price,
        messageCount: thread.messagesLength,
      })
    } catch (error) {
      console.error('Error retrieving thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to retrieve thread: ${errorMessage}` })
    }
  })

  /**
   * PUT /api/projects/:projectName/threads/:threadId
   * Update a thread (currently supports renaming)
   *
   * Body: { name?: string }
   */
  app.put('/api/projects/:projectName/threads/:threadId', async (req: express.Request, res: express.Response) => {
    try {
      const { projectName, threadId } = req.params
      if (!projectName || !threadId) {
        res.status(400).json({ error: 'Project name and thread ID are required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      const { name } = req.body

      // Verify thread exists and user owns it
      const existingThread = await threadService.getThread(projectName, threadId)
      if (!existingThread) {
        res.status(404).json({ error: `Thread '${threadId}' not found in project '${projectName}'` })
        return
      }

      if (existingThread.username !== username) {
        res.status(403).json({ error: 'Access denied: thread belongs to another user' })
        return
      }

      debugLog('THREAD', `PUT update thread: ${threadId} in project: ${projectName}`)
      const updatedThread = await threadService.updateThread(projectName, threadId, { name })

      res.status(200).json({
        success: true,
        thread: {
          id: updatedThread.id,
          name: updatedThread.name,
          projectId: updatedThread.projectId,
          modifiedDate: updatedThread.modifiedDate,
        },
      })
    } catch (error) {
      console.error('Error updating thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to update thread: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/threads/:threadId/stop
   * Stop the current run for a thread
   */
  app.post('/api/projects/:projectName/threads/:threadId/stop', (req: express.Request, res: express.Response) => {
    try {
      const { projectName, threadId } = req.params
      if (!projectName || !threadId) {
        res.status(400).json({ error: 'Project name and thread ID are required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      debugLog('THREAD', `POST stop thread: ${threadId} in project: ${projectName}`)
      threadCodayManager.stop(threadId)

      res.status(200).json({
        success: true,
        message: 'Stop signal sent successfully',
      })
    } catch (error) {
      console.error('Error stopping thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to stop thread: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:projectName/threads/:threadId
   * Delete a thread
   */
  app.delete('/api/projects/:projectName/threads/:threadId', async (req: express.Request, res: express.Response) => {
    try {
      const { projectName, threadId } = req.params
      if (!projectName || !threadId) {
        res.status(400).json({ error: 'Project name and thread ID are required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      // Verify thread exists and user owns it
      const existingThread = await threadService.getThread(projectName, threadId)
      if (!existingThread) {
        res.status(404).json({ error: `Thread '${threadId}' not found in project '${projectName}'` })
        return
      }

      if (existingThread.username !== username) {
        res.status(403).json({ error: 'Access denied: thread belongs to another user' })
        return
      }

      debugLog('THREAD', `DELETE thread: ${threadId} from project: ${projectName}`)
      const deleted = await threadService.deleteThread(projectName, threadId)

      if (deleted) {
        res.status(200).json({
          success: true,
          message: `Thread '${threadId}' deleted successfully`,
        })
      } else {
        res.status(404).json({ error: `Thread '${threadId}' not found` })
      }
    } catch (error) {
      console.error('Error deleting thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to delete thread: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/threads/:threadId/upload
   * Upload a file (image) to a thread
   *
   * Body: {
   *   content: string (base64),
   *   mimeType: string,
   *   filename: string
   * }
   */
  app.post(
    '/api/projects/:projectName/threads/:threadId/upload',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId } = req.params
        const { content, mimeType, filename } = req.body

        // Validate required fields
        if (!projectName || !threadId) {
          res.status(400).json({ error: 'Project name and thread ID are required' })
          return
        }

        if (!content || !mimeType || !filename) {
          res.status(400).json({ error: 'Missing required fields: content, mimeType, filename' })
          return
        }

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).json({ error: 'Authentication required' })
          return
        }

        debugLog('UPLOAD', `threadId: ${threadId}, project: ${projectName}, uploading: ${filename}`)

        // Get the thread instance
        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).json({ error: 'Thread not found or not active' })
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).json({ error: 'Access denied: thread belongs to another user' })
          return
        }

        // Process the image
        const buffer = Buffer.from(content, 'base64')
        const processed = await processImageBuffer(buffer, mimeType)

        // Create ImageContent
        const imageContent: ImageContent = {
          type: 'image',
          content: processed.content,
          mimeType: processed.mimeType,
          width: processed.width,
          height: processed.height,
          source: `${filename} (${(processed.processedSize / 1024).toFixed(1)} KB)`,
        }

        // Upload to thread via Coday
        instance.coday.upload([imageContent])

        res.status(200).json({
          success: true,
          processedSize: processed.processedSize,
          dimensions: { width: processed.width, height: processed.height },
        })
      } catch (error) {
        console.error('Error processing file upload:', error)
        const errorMessage = error instanceof Error ? error.message : 'Upload failed'
        res.status(400).json({ error: errorMessage })
      }
    }
  )
}
