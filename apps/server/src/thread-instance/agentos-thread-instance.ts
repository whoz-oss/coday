import { Response } from 'express'
import axios from 'axios'
import { debugLog } from '../log'
import { ThreadInstance } from './thread-instance.interface'
import { HeartBeatEvent, InviteEvent } from '@coday/coday-events'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * AgentOS remote execution instance for a thread.
 * Proxies execution to AgentOS backend via HTTP/SSE.
 */
export class AgentOSThreadInstance implements ThreadInstance {
  private readonly connections: Set<Response> = new Set()
  private lastActivity: number = Date.now()
  private disconnectTimeout?: NodeJS.Timeout
  private inactivityTimeout?: NodeJS.Timeout
  private isOneshot: boolean = false
  private agentOSCaseId?: string
  private sseAbortController?: AbortController
  private sseConnected: boolean = false

  readonly coday = undefined // AgentOS instances don't have local Coday

  // Timeouts configuration
  static readonly DISCONNECT_TIMEOUT = 5 * 60 * 1000
  static readonly INTERACTIVE_TIMEOUT = 8 * 60 * 60 * 1000
  static readonly ONESHOT_TIMEOUT = 30 * 60 * 1000

  constructor(
    public readonly threadId: string,
    public readonly projectName: string,
    public readonly username: string,
    private readonly agentosUrl: string,
    private readonly projectId: string,
    private readonly onTimeout: (threadId: string) => void
  ) {
    this.resetInactivityTimeout()
  }

  get connectionCount(): number {
    return this.connections.size
  }

  addConnection(response: Response): void {
    if (this.connections.has(response)) {
      return
    }

    this.connections.add(response)
    this.updateActivity()
    this.isOneshot = false
    debugLog('AGENTOS_THREAD', `Added SSE connection to thread ${this.threadId} (total: ${this.connections.size})`)

    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
      debugLog('AGENTOS_THREAD', `Cleared disconnect timeout for thread ${this.threadId}`)
    }
  }

  removeConnection(response: Response): void {
    this.connections.delete(response)
    debugLog(
      'AGENTOS_THREAD',
      `Removed SSE connection from thread ${this.threadId} (remaining: ${this.connections.size})`
    )

    if (this.connections.size === 0 && !this.disconnectTimeout) {
      debugLog('AGENTOS_THREAD', `No connections remaining for thread ${this.threadId}, starting disconnect timeout`)
      this.disconnectTimeout = setTimeout(() => {
        debugLog('AGENTOS_THREAD', `Disconnect timeout reached for thread ${this.threadId}`)
        this.onTimeout(this.threadId)
      }, AgentOSThreadInstance.DISCONNECT_TIMEOUT)
    }
  }

  private updateActivity(): void {
    this.lastActivity = Date.now()
    this.resetInactivityTimeout()
  }

  private resetInactivityTimeout(): void {
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
    }

    const timeout = this.isOneshot ? AgentOSThreadInstance.ONESHOT_TIMEOUT : AgentOSThreadInstance.INTERACTIVE_TIMEOUT

    this.inactivityTimeout = setTimeout(() => {
      const inactiveTime = Date.now() - this.lastActivity
      debugLog(
        'AGENTOS_THREAD',
        `Inactivity timeout reached for thread ${this.threadId} after ${Math.round(inactiveTime / 1000)}s`
      )
      this.onTimeout(this.threadId)
    }, timeout)
  }

  markAsOneshot(): void {
    this.isOneshot = true
    this.resetInactivityTimeout()
  }

  getInactiveTime(): number {
    return Date.now() - this.lastActivity
  }

  async prepareInstance(): Promise<boolean> {
    this.updateActivity()

    if (this.agentOSCaseId) {
      debugLog('AGENTOS_THREAD', `AgentOS case already exists for thread ${this.threadId}: ${this.agentOSCaseId}`)
      return false
    }

    debugLog('AGENTOS_THREAD', `Creating AgentOS case for thread ${this.threadId}`)

    try {
      // Create case in AgentOS
      const response = await axios.post(`${this.agentosUrl}/api/cases`, {
        projectId: this.projectId,
      })

      this.agentOSCaseId = response.data.id
      debugLog('AGENTOS_THREAD', `Created AgentOS case ${this.agentOSCaseId} for thread ${this.threadId}`)

      // Connect to SSE stream (non-blocking, runs in background)
      this.connectToEventStream()

      // Send initial InviteEvent immediately to unblock frontend
      const inviteEvent = new InviteEvent({
        invite: 'How can I help you?',
      })
      this.broadcastEvent(inviteEvent)
      debugLog('AGENTOS_THREAD', `Sent initial InviteEvent for thread ${this.threadId}`)

      return true
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      debugLog('AGENTOS_THREAD', `Error creating AgentOS case: ${errorMessage}`)
      throw error
    }
  }

  async startExecution(): Promise<boolean> {
    // For AgentOS, prepare and start are the same (case starts on creation)
    return this.prepareInstance()
  }

  private connectToEventStream(): void {
    if (!this.agentOSCaseId) {
      debugLog('AGENTOS_THREAD', 'Cannot connect to event stream: no case ID')
      return
    }

    if (this.sseConnected) {
      debugLog('AGENTOS_THREAD', `Already connected to event stream for case ${this.agentOSCaseId}`)
      return
    }

    debugLog('AGENTOS_THREAD', `Connecting to AgentOS event stream for case ${this.agentOSCaseId}`)

    this.sseAbortController = new AbortController()

    // Start connection (non-blocking, runs in background)
    axios
      .get(`${this.agentosUrl}/api/cases/${this.agentOSCaseId}/events`, {
        responseType: 'stream',
        headers: {
          Accept: 'text/event-stream',
        },
        signal: this.sseAbortController.signal,
      })
      .then((response) => {
        this.sseConnected = true
        debugLog('AGENTOS_THREAD', `Connected to AgentOS event stream for case ${this.agentOSCaseId}`)

        let buffer = ''
        response.data.on('data', (chunk: Buffer) => {
          const chunkStr = chunk.toString()
          debugLog('AGENTOS_SSE_RAW', `Received ${chunk.length} bytes: ${chunkStr}`)
          buffer += chunkStr

          const messages = buffer.split('\n\n')
          buffer = messages.pop() || ''

          debugLog(
            'AGENTOS_SSE_PARSE',
            `Split into ${messages.length} messages, buffer remaining: ${buffer.length} chars`
          )

          for (const message of messages) {
            if (!message.trim()) {
              debugLog('AGENTOS_SSE_PARSE', 'Skipping empty message')
              continue
            }

            debugLog('AGENTOS_SSE_PARSE', `Processing message: ${message}`)

            const lines = message.split('\n')
            let eventType = 'message'
            let eventId = ''
            let data = ''

            for (const line of lines) {
              if (line.startsWith('event:')) {
                eventType = line.substring(6).trim()
              } else if (line.startsWith('id:')) {
                eventId = line.substring(3).trim()
              } else if (line.startsWith('data:')) {
                data = line.substring(5).trim()
              }
            }

            if (data) {
              debugLog(
                'AGENTOS_SSE_PARSE',
                `Parsed event - type: ${eventType}, id: ${eventId}, data length: ${data.length}`
              )
              this.handleAgentOSEvent(eventType, eventId, data)
            } else {
              debugLog('AGENTOS_SSE_PARSE', 'No data found in message')
            }
          }
        })

        response.data.on('end', () => {
          debugLog('AGENTOS_THREAD', `AgentOS event stream ended for case ${this.agentOSCaseId}`)
          this.sseConnected = false
        })

        response.data.on('error', (error: Error) => {
          debugLog('AGENTOS_THREAD', `AgentOS event stream error: ${error.message}`)
          this.sseConnected = false
          this.broadcastEvent({ type: 'error', message: error.message })
        })
      })
      .catch((error) => {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        debugLog('AGENTOS_THREAD', `Error connecting to event stream: ${errorMessage}`)
        this.sseConnected = false
        this.broadcastEvent({ type: 'error', message: errorMessage })
      })
  }

  private handleAgentOSEvent(eventType: string, eventId: string, data: string): void {
    debugLog('AGENTOS_EVENT', `${eventType} (${eventId}): ${data}`)

    try {
      const parsedData = JSON.parse(data)

      // Map AgentOS event types to Coday event format
      let codayEvent: any

      switch (eventType) {
        case 'message':
          // Map AgentOS MessageEvent to Coday MessageEvent
          // Convert actor role from AgentOS format to Coday format
          const actorRole =
            parsedData.actor?.role === 'AGENT' ? 'assistant' : parsedData.actor?.role === 'USER' ? 'user' : 'assistant'

          // Extract text content from AgentOS format
          let messageContent: any[]
          if (parsedData.content && Array.isArray(parsedData.content)) {
            // AgentOS sends content as array of {content: string}
            messageContent = parsedData.content.map((c: any) => ({
              type: 'text',
              content: c.content || '',
            }))
          } else {
            messageContent = [{ type: 'text', content: '' }]
          }

          codayEvent = {
            type: 'message',
            timestamp: eventId,
            role: actorRole,
            name: parsedData.actor?.displayName || (actorRole === 'user' ? 'User' : 'Agent'),
            content: messageContent,
          }
          debugLog(
            'AGENTOS_EVENT',
            `Mapped message event - role: ${actorRole}, name: ${codayEvent.name}, content length: ${messageContent[0]?.content?.length || 0}`
          )
          break

        case 'thinking':
          codayEvent = {
            type: 'thinking',
            timestamp: eventId,
          }
          break

        case 'tool_request':
          codayEvent = {
            type: 'tool_request',
            timestamp: eventId,
            toolRequestId: parsedData.toolRequestId || eventId,
            name: parsedData.toolName,
            args: JSON.stringify(parsedData.args || {}),
          }
          break

        case 'tool_response':
          codayEvent = {
            type: 'tool_response',
            timestamp: eventId,
            toolRequestId: parsedData.toolRequestId || eventId,
            output: typeof parsedData.output === 'string' ? parsedData.output : JSON.stringify(parsedData.output),
          }
          break

        case 'agent_selected':
        case 'agent_running':
        case 'agent_finished':
          // Ignore agent lifecycle events for now (not displayed in Coday UI)
          debugLog('AGENTOS_EVENT', `Ignoring agent lifecycle event: ${eventType}`)
          return

        case 'warning':
          codayEvent = {
            type: 'warn',
            timestamp: eventId,
            warning: parsedData.message || data,
          }
          break

        case 'error':
          codayEvent = {
            type: 'error',
            timestamp: eventId,
            error: parsedData.message || data,
          }
          break

        case 'status':
          // Ignore case status events (internal to AgentOS)
          debugLog('AGENTOS_EVENT', `Ignoring case status event: ${parsedData.status}`)
          return

        case 'text_chunk':
          // Map to TextChunkEvent (streaming response)
          const chunk = parsedData.chunk || ''
          codayEvent = {
            type: 'text_chunk',
            timestamp: eventId,
            chunk: chunk,
          }
          debugLog('AGENTOS_EVENT', `Mapped text_chunk - length: ${chunk.length}`)
          break

        default:
          debugLog('AGENTOS_EVENT', `Unknown event type: ${eventType}`)
          return
      }

      this.broadcastEvent(codayEvent)
    } catch (error) {
      debugLog('AGENTOS_EVENT', `Error parsing event data: ${error}`)
    }
  }

  async sendMessage(content: string, answerToEventId?: string): Promise<void> {
    if (!this.agentOSCaseId) {
      throw new Error('Cannot send message: no case ID')
    }

    debugLog('AGENTOS_THREAD', `Sending message to AgentOS case ${this.agentOSCaseId}`)

    // AgentOS expects UUID for answerToEventId, but Coday uses timestamps
    // For POC: ignore answerToEventId if it's not a valid UUID
    const isValidUUID = answerToEventId && UUID_PATTERN.test(answerToEventId)

    if (answerToEventId && !isValidUUID) {
      debugLog('AGENTOS_THREAD', `Ignoring non-UUID answerToEventId: ${answerToEventId}`)
    }

    try {
      await axios.post(`${this.agentosUrl}/api/cases/${this.agentOSCaseId}/messages`, {
        content,
        userId: this.username,
        answerToEventId: isValidUUID ? answerToEventId : undefined,
      })
      debugLog('AGENTOS_THREAD', `Message sent to AgentOS case ${this.agentOSCaseId}`)
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      debugLog('AGENTOS_THREAD', `Error sending message: ${errorMessage}`)
      throw error
    }
  }

  sendHeartbeat(): void {
    if (this.connections.size === 0) {
      return
    }

    try {
      const heartBeatEvent = new HeartBeatEvent({})
      debugLog(
        'AGENTOS_THREAD',
        `Sending heartbeat to ${this.connections.size} connection(s) for thread ${this.threadId}`
      )
      this.broadcastEvent(heartBeatEvent)
    } catch (error) {
      debugLog('AGENTOS_THREAD', `Error sending heartbeat for thread ${this.threadId}:`, error)
    }
  }

  private broadcastEvent(event: any): void {
    const data = `data: ${JSON.stringify(event)}\n\n`

    for (const connection of this.connections) {
      try {
        if (!connection.writableEnded) {
          connection.write(data)
        } else {
          this.connections.delete(connection)
        }
      } catch (error) {
        debugLog('AGENTOS_THREAD', `Error broadcasting to connection:`, error)
        this.connections.delete(connection)
      }
    }
  }

  stop(): void {
    if (!this.agentOSCaseId) {
      return
    }

    debugLog('AGENTOS_THREAD', `Stopping AgentOS case ${this.agentOSCaseId}`)

    axios
      .post(`${this.agentosUrl}/api/cases/${this.agentOSCaseId}/stop`)
      .then(() => {
        debugLog('AGENTOS_THREAD', `AgentOS case ${this.agentOSCaseId} stopped`)
      })
      .catch((error) => {
        debugLog('AGENTOS_THREAD', `Error stopping AgentOS case: ${error.message}`)
      })
  }

  async cleanup(): Promise<void> {
    debugLog('AGENTOS_THREAD', `Cleaning up thread ${this.threadId}`)

    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
    }
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
      this.inactivityTimeout = undefined
    }

    for (const connection of this.connections) {
      try {
        connection.end()
      } catch (error) {
        debugLog('AGENTOS_THREAD', `Error closing connection:`, error)
      }
    }
    this.connections.clear()

    if (this.sseAbortController) {
      this.sseAbortController.abort()
      this.sseAbortController = undefined
    }

    if (this.agentOSCaseId) {
      try {
        await axios.delete(`${this.agentosUrl}/api/cases/${this.agentOSCaseId}`)
        debugLog('AGENTOS_THREAD', `Deleted AgentOS case ${this.agentOSCaseId}`)
      } catch (error) {
        debugLog('AGENTOS_THREAD', `Error deleting AgentOS case: ${error}`)
      }
      this.agentOSCaseId = undefined
    }

    this.sseConnected = false
  }
}
