import express from 'express'
import crypto from 'node:crypto'
import { ProjectService, ThreadService } from '@coday/service'
import { CodayOptions, AnswerEvent, InviteEvent } from '@coday/model'
import { filter, take } from 'rxjs'
import { stripBotMention, shouldHandleMessage, buildThreadKey } from './slack-socket-mode'

const SLACK_SIGNATURE_HEADER = 'x-slack-signature'
const SLACK_TIMESTAMP_HEADER = 'x-slack-request-timestamp'
const FIVE_MINUTES_SECONDS = 60 * 5

type SlackLogger = (scope: string, message: string, ...args: unknown[]) => void

type ThreadCodayInstance = {
  prepareCoday: () => boolean
  coday?: {
    run: () => Promise<void>
    interactor: {
      events: any
      sendEvent: (event: any) => void
      replayLastInvite: () => void
    }
  }
}

type ThreadCodayManagerLike = {
  get: (threadId: string) => ThreadCodayInstance | undefined
  createWithoutConnection: (
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions
  ) => ThreadCodayInstance
  cleanup: (threadId: string) => Promise<void>
}

type SlackIntegrationConfig = {
  apiKey?: string
  signingSecret?: string
  project?: string
  username?: string
  requireMention?: boolean
  botUserId?: string
  respondInThread?: boolean
  channelAllowlist?: string[]
  threadMap?: Record<string, string>
  autoCreateThreads?: boolean // Auto-create Coday threads for new Slack channels (default: false)
}

type SlackEventPayload = {
  type: string
  challenge?: string
  event?: {
    type?: string
    subtype?: string
    user?: string
    bot_id?: string
    text?: string
    channel?: string
    ts?: string
    thread_ts?: string
    channel_type?: string
  }
}

export function getRawBody(req: express.Request): string {
  const raw = (req as any).rawBody
  if (raw && Buffer.isBuffer(raw)) {
    return raw.toString('utf8')
  }
  return ''
}

export function verifySlackSignature(
  rawBody: string,
  signingSecret: string,
  signature: string,
  timestamp: string
): boolean {
  if (!signature || !timestamp) return false

  const ts = Number(timestamp)
  if (!Number.isFinite(ts)) return false

  const now = Math.floor(Date.now() / 1000)
  if (Math.abs(now - ts) > FIVE_MINUTES_SECONDS) return false

  const baseString = `v0:${timestamp}:${rawBody}`
  const hmac = crypto.createHmac('sha256', signingSecret).update(baseString).digest('hex')
  const expected = `v0=${hmac}`

  try {
    return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signature))
  } catch {
    return false
  }
}

// Note: stripBotMention, shouldHandleMessage, and buildThreadKey are imported from slack-socket-mode.ts
// to avoid duplication and ensure consistent behavior

export function registerSlackRoutes(
  app: express.Application,
  projectService: ProjectService,
  threadService: ThreadService,
  threadCodayManager: ThreadCodayManagerLike,
  codayOptions: CodayOptions,
  logger: SlackLogger = console.log
): void {
  logger('SLACK', 'Slack routes registered at /api/slack/events')

  // Health check endpoint for Slack webhook
  app.get('/api/slack/health', (_req: express.Request, res: express.Response) => {
    logger('SLACK', 'Health check requested')
    res.status(200).json({
      status: 'ok',
      message: 'Slack webhook endpoint is active',
      timestamp: new Date().toISOString(),
    })
  })

  app.post('/api/slack/events', async (req: express.Request, res: express.Response) => {
    logger('SLACK', 'Received Slack webhook request')
    const rawBody = getRawBody(req)
    let payload: SlackEventPayload | null = null

    try {
      payload = JSON.parse(rawBody) as SlackEventPayload
      logger('SLACK', `Payload type: ${payload.type}, event type: ${payload.event?.type}`)
    } catch (error) {
      logger('SLACK', `Failed to parse JSON: ${error}`)
      res.status(400).send('Invalid JSON')
      return
    }

    const signature = req.headers[SLACK_SIGNATURE_HEADER] as string
    const timestamp = req.headers[SLACK_TIMESTAMP_HEADER] as string

    logger('SLACK', `Signature header present: ${!!signature}, Timestamp present: ${!!timestamp}`)

    const projects = projectService.listProjects()
    logger('SLACK', `Checking ${projects.length} projects for matching signature`)
    let matchedProjectName: string | null = null

    for (const project of projects) {
      const projectInfo = projectService.getProject(project.name)
      const candidate = (projectInfo?.config.integration?.SLACK || {}) as SlackIntegrationConfig
      if (!candidate.signingSecret) {
        logger('SLACK', `Project ${project.name}: No signing secret configured`)
        continue
      }

      logger('SLACK', `Project ${project.name}: Verifying signature...`)
      if (verifySlackSignature(rawBody, candidate.signingSecret, signature, timestamp)) {
        matchedProjectName = project.name
        logger('SLACK', `Project ${project.name}: Signature verified ✓`)
        break
      } else {
        logger('SLACK', `Project ${project.name}: Signature verification failed ✗`)
      }
    }

    if (!matchedProjectName) {
      logger('SLACK', 'No project matched the Slack signature')
      res.status(401).send('Invalid Slack signature')
      return
    }

    logger('SLACK', `Matched project: ${matchedProjectName}`)

    if (payload.type === 'url_verification') {
      res.status(200).send({ challenge: payload.challenge })
      return
    }

    // ACK early to satisfy Slack timing constraints
    res.status(200).send('OK')

    const projectName = matchedProjectName

    const project = projectService.getProject(projectName)
    if (!project) {
      logger('SLACK', `Project '${projectName}' not found`)
      return
    }

    const config = (project.config.integration?.SLACK || {}) as SlackIntegrationConfig
    if (!config.apiKey || !config.username) {
      logger('SLACK', 'Slack apiKey or username missing in integration config')
      return
    }

    const event = payload.event
    logger(
      'SLACK',
      `Event details: type=${event?.type}, subtype=${event?.subtype}, channel_type=${event?.channel_type}, user=${event?.user}, text=${event?.text?.substring(0, 50)}...`
    )

    if (!event) {
      logger('SLACK', 'No event in payload')
      return
    }

    const filterResult = shouldHandleMessage(event, config)
    if (!filterResult.allowed) {
      logger('SLACK', `Message filtered out: ${filterResult.reason}`)
      return
    }

    logger('SLACK', 'Message passed filters, proceeding to handle')

    const channel = event!.channel!
    const threadKey = buildThreadKey(channel, event!.thread_ts, event!.ts)

    logger('SLACK', `Channel: ${channel}, ThreadKey: ${threadKey}`)
    logger('SLACK', `Current threadMap keys: [${Object.keys(config.threadMap || {}).join(', ')}]`)

    // Find or create Coday thread
    let threadId = config.threadMap?.[threadKey]

    if (!threadId) {
      // Check if auto-creation is enabled
      if (!config.autoCreateThreads) {
        logger('SLACK', `No existing thread for key "${threadKey}" and autoCreateThreads is disabled, ignoring message`)
        return
      }

      logger('SLACK', `No existing thread found for key "${threadKey}", creating new thread`)

      // Fetch channel name from Slack API for better readability
      let channelName = channel // fallback to channel ID
      try {
        const response = await fetch(`https://slack.com/api/conversations.info?channel=${channel}`, {
          headers: { Authorization: `Bearer ${config.apiKey}` },
        })
        const data = (await response.json()) as { ok: boolean; channel?: { name?: string } }
        if (data.ok && data.channel?.name) {
          channelName = data.channel.name
          logger('SLACK', `Resolved channel name: ${channelName}`)
        }
      } catch (error) {
        logger(
          'SLACK',
          `Could not fetch channel name, using ID: ${error instanceof Error ? error.message : String(error)}`
        )
      }

      const threadName = `slack:${channelName}`
      const thread = await threadService.createThread(projectName, config.username, threadName)
      threadId = thread.id

      logger('SLACK', `Created new thread: ${threadId} with name: ${threadName}`)

      const updatedMap = { ...(config.threadMap || {}), [threadKey]: threadId }
      const updatedConfig = { ...config, threadMap: updatedMap }
      const updatedIntegration = { ...(project.config.integration || {}), SLACK: updatedConfig }
      projectService.updateProjectConfig(projectName, { ...project.config, integration: updatedIntegration })

      logger('SLACK', `Updated threadMap, now has keys: [${Object.keys(updatedMap).join(', ')}]`)
    } else {
      logger('SLACK', `Found existing thread: ${threadId} for key "${threadKey}"`)
    }

    const prompt = stripBotMention(event!.text!, config.botUserId)
    if (!prompt) {
      return
    }

    // Get or create a Coday instance for this thread (without SSE connection)
    // This ensures we reuse the same instance and maintain conversation context
    let instance = threadCodayManager.get(threadId)

    if (!instance) {
      logger('SLACK', `Creating Coday instance for thread ${threadId}`)

      const slackOptions: CodayOptions = {
        ...codayOptions,
        oneshot: false, // Not oneshot - keep instance alive
        project: projectName,
        thread: threadId,
        prompts: [prompt], // Add the message as initial prompt
      }

      instance = threadCodayManager.createWithoutConnection(threadId, projectName, config.username, slackOptions)
      instance.prepareCoday()

      // Start the Coday run in the background
      instance.coday!.run().catch((error) => {
        logger('SLACK', `Error in Coday run: ${error instanceof Error ? error.message : String(error)}`)
      })
    } else {
      // Instance already exists - need to send message to it
      if (!instance.coday) {
        logger('SLACK', `Failed to get Coday instance for thread ${threadId}`)
        return
      }

      logger('SLACK', `Sending message to existing thread ${threadId}`)

      // Subscribe FIRST, then replay
      // This is important because replay happens synchronously
      const invitePromise = new Promise<InviteEvent>((resolve, reject) => {
        const timeoutHandle = setTimeout(() => reject(new Error('Timeout waiting for InviteEvent')), 1000)

        instance!
          .coday!.interactor.events.pipe(
            filter((event): event is InviteEvent => event instanceof InviteEvent),
            take(1)
          )
          .subscribe({
            next: (inviteEvent: InviteEvent) => {
              clearTimeout(timeoutHandle)
              resolve(inviteEvent)
            },
            error: reject,
          })
      })

      // Now replay the last invite (will emit immediately if it exists)
      logger('SLACK', `Calling replayLastInvite()...`)
      instance.coday.interactor.replayLastInvite()
      logger('SLACK', `replayLastInvite() called`)

      // Wait for the invite and respond
      invitePromise
        .then((inviteEvent: InviteEvent) => {
          logger('SLACK', `✓ Received InviteEvent with timestamp: ${inviteEvent.timestamp}`)
          logger('SLACK', `Building answer with parentKey...`)
          const answerEvent = inviteEvent.buildAnswer(prompt)
          logger('SLACK', `Sending answer event with parentKey: ${answerEvent.parentKey}`)
          instance!.coday!.interactor.sendEvent(answerEvent)
        })
        .catch((error: Error) => {
          logger('SLACK', `✗ Could not get InviteEvent: ${error.message}`)
          logger('SLACK', `Fallback: sending AnswerEvent without parentKey`)
          instance!.coday!.interactor.sendEvent(new AnswerEvent({ answer: prompt }))
        })
    }

    // Note: Response will be sent to Slack automatically via forwardEventToSlack
    // in ThreadCodayInstance when the assistant generates a response
  })
}
