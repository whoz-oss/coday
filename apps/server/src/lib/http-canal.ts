import express from 'express'
import { CanalBridge, CommunicationCanal, MessageEvent } from '@coday/model'
import { ProjectService } from '@coday/service'
import { debugLog } from './log'

/**
 * HTTP Canal — a simple request/response communication channel adapter.
 *
 * This is Story 2 of the Communication Canal abstraction (issue #518).
 * Its purpose is to validate that the CommunicationCanal port generalizes
 * beyond Slack — a second adapter with a fundamentally different shape:
 * - No external SDK, no auth signatures, no persistent connection
 * - Plain HTTP POST to send a message, optionally waiting for the response
 * - Conversation continuity via a caller-provided conversationId
 *
 * Endpoints:
 *   POST /api/canal/http/:projectName
 *   Body: {
 *     message: string          — the user message
 *     username: string         — who is sending
 *     conversationId?: string  — caller's key for conversation continuity
 *     threadName?: string      — human-readable name for new threads
 *     awaitResponse?: boolean  — if true, wait for first assistant reply (default: false)
 *     timeoutMs?: number       — max wait time when awaitResponse=true (default: 30000)
 *   }
 *   Response (async):  { threadId, conversationId }
 *   Response (sync):   { threadId, conversationId, response: string }
 */
export class HttpCanal implements CommunicationCanal {
  readonly name = 'http'

  private bridge?: CanalBridge
  // conversationId → threadId mapping (in-memory; cleared on restart)
  private conversationMap: Map<string, string> = new Map()

  constructor(
    private readonly app: express.Application,
    private readonly projectService: ProjectService
  ) {}

  registerRoutes(): void {
    debugLog('HTTP_CANAL', 'Registering HTTP canal routes at /api/canal/http/:projectName')

    this.app.post(
      '/api/canal/http/:projectName',
      async (req: express.Request<{ projectName: string }>, res: express.Response) => {
        if (!this.bridge) {
          res.status(503).json({ error: 'Canal not yet initialized' })
          return
        }

        const { projectName }: { projectName: string } = req.params
        const { message, username, conversationId, threadName, awaitResponse, timeoutMs } = req.body

        // Validate required fields
        if (!message || typeof message !== 'string') {
          res.status(400).json({ error: 'message is required and must be a string' })
          return
        }
        if (!username || typeof username !== 'string') {
          res.status(400).json({ error: 'username is required and must be a string' })
          return
        }

        // Validate project exists
        const project = this.projectService.getProject(projectName)
        if (!project) {
          res.status(404).json({ error: `Project '${projectName}' not found` })
          return
        }

        debugLog('HTTP_CANAL', `Message from ${username} for project ${projectName}, conversationId=${conversationId}`)

        try {
          const resolvedConversationId = conversationId || `http:${Date.now()}:${Math.random().toString(36).slice(2)}`
          let threadId = this.conversationMap.get(resolvedConversationId)

          if (!threadId) {
            // New conversation — create a thread
            const name = threadName || `http:${resolvedConversationId}`
            const handle = await this.bridge.getOrCreateThread(projectName, username, resolvedConversationId, name, {
              initialPrompt: message,
            })
            threadId = handle.threadId
            this.conversationMap.set(resolvedConversationId, threadId)

            debugLog('HTTP_CANAL', `Created thread ${threadId} for conversationId ${resolvedConversationId}`)

            if (!awaitResponse) {
              res.status(201).json({ threadId, conversationId: resolvedConversationId })
              return
            }

            // Sync mode: subscribe to the handle's events and wait for assistant reply
            const response = await this.waitForResponse(handle, timeoutMs ?? 30_000)
            res.status(200).json({ threadId, conversationId: resolvedConversationId, response })
          } else {
            // Existing conversation — inject message into running thread
            debugLog('HTTP_CANAL', `Sending to existing thread ${threadId}`)

            if (!awaitResponse) {
              this.bridge.sendMessage(threadId, message)
              res.status(200).json({ threadId, conversationId: resolvedConversationId })
              return
            }

            // Sync mode: subscribe then send
            const handle = this.bridge.getExistingThread(threadId, projectName, username)
            const responsePromise = this.waitForResponse(handle, timeoutMs ?? 30_000)
            this.bridge.sendMessage(threadId, message)
            const response = await responsePromise
            res.status(200).json({ threadId, conversationId: resolvedConversationId, response })
          }
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error)
          debugLog('HTTP_CANAL', `Error handling request:`, error)
          if (message.includes('timeout')) {
            res.status(504).json({ error: 'Response timeout — request is still processing', details: message })
          } else {
            res.status(500).json({ error: 'Internal error', details: message })
          }
        }
      }
    )

    // Health check
    this.app.get('/api/canal/http/health', (_req, res) => {
      res.status(200).json({
        status: 'ok',
        canal: this.name,
        conversations: this.conversationMap.size,
        timestamp: new Date().toISOString(),
      })
    })
  }

  async initialize(bridge: CanalBridge): Promise<void> {
    this.bridge = bridge
    debugLog('HTTP_CANAL', 'HTTP canal initialized')
  }

  async shutdown(): Promise<void> {
    this.conversationMap.clear()
    debugLog('HTTP_CANAL', 'HTTP canal shut down')
  }

  /**
   * Wait for the first assistant MessageEvent from a thread handle.
   * Returns the text content of the response.
   */
  private waitForResponse(handle: ReturnType<CanalBridge['getExistingThread']>, timeoutMs: number): Promise<string> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        unsubscribe()
        reject(new Error(`Response timeout after ${timeoutMs}ms`))
      }, timeoutMs)

      const unsubscribe = handle.onEvent((event) => {
        if (event instanceof MessageEvent && event.role === 'assistant') {
          const text = event.getTextContent()
          if (text) {
            clearTimeout(timeout)
            unsubscribe()
            resolve(text)
          }
        }
      })
    })
  }
}
