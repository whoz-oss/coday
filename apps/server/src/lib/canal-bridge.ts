import { AnswerEvent, CanalBridge, CanalThreadHandle, CodayEvent, CodayOptions, InviteEvent } from '@coday/model'
import { ThreadService } from '@coday/service'
import { ThreadCodayManager } from './thread-coday-manager'
import { debugLog } from './log'
import { filter, take } from 'rxjs'

/**
 * Wraps a thread instance's interactor event stream as a CanalThreadHandle.
 * The adapter subscribes to events; the core never calls outward.
 */
class CanalThreadHandleImpl implements CanalThreadHandle {
  constructor(
    readonly threadId: string,
    private readonly threadCodayManager: ThreadCodayManager
  ) {}

  onEvent(handler: (event: CodayEvent) => void): () => void {
    const instance = this.threadCodayManager.get(this.threadId)
    if (!instance?.coday) {
      debugLog('CANAL_BRIDGE', `No coday instance found for thread ${this.threadId}`)
      return () => {}
    }
    const sub = instance.coday.interactor.events.subscribe(handler)
    return () => sub.unsubscribe()
  }
}

/**
 * Implements CanalBridge by wrapping ThreadCodayManager and ThreadService.
 * This is the adapter between the canal port and the core runtime.
 */
export class CanalBridgeImpl implements CanalBridge {
  constructor(
    private readonly threadCodayManager: ThreadCodayManager,
    private readonly threadService: ThreadService,
    private readonly codayOptions: CodayOptions
  ) {}

  async getOrCreateThread(
    projectName: string,
    username: string,
    externalKey: string,
    threadName: string,
    options: Record<string, unknown>
  ): Promise<CanalThreadHandle> {
    // The canal adapter tracks externalKey → threadId mapping.
    // The bridge creates the Coday thread and its instance.

    const thread = await this.threadService.createThread(projectName, username, threadName)
    const threadId = thread.id

    debugLog('CANAL_BRIDGE', `Created thread ${threadId} for externalKey "${externalKey}" in project ${projectName}`)

    // Pass initial prompt if provided so the run loop picks it up immediately
    const initialPrompt = options.initialPrompt as string | undefined
    return this.ensureInstance(threadId, projectName, username, initialPrompt)
  }

  /**
   * Get a handle to an existing thread by its threadId.
   * Used when re-attaching after server restart.
   * Creates a Coday instance if one doesn't already exist, but does NOT create a new thread.
   */
  getExistingThread(threadId: string, projectName: string, username: string): CanalThreadHandle {
    return this.ensureInstance(threadId, projectName, username)
  }

  /**
   * Send a message into a running Coday thread.
   * If the thread has an active InviteEvent (waiting for input), responds to it.
   * Otherwise sends a bare AnswerEvent as a fallback.
   */
  sendMessage(threadId: string, message: string): void {
    const instance = this.threadCodayManager.get(threadId)
    if (!instance?.coday) {
      debugLog('CANAL_BRIDGE', `Cannot send message: no coday instance for thread ${threadId}`)
      return
    }

    const interactor = instance.coday.interactor

    // Use the InviteEvent pattern: subscribe first, then replay.
    // This mirrors the original Slack integration's approach for existing instances.
    const invitePromise = new Promise<InviteEvent>((resolve, reject) => {
      const timeoutHandle = setTimeout(() => reject(new Error('Timeout waiting for InviteEvent')), 1000)
      interactor.events
        .pipe(
          filter((event): event is InviteEvent => event instanceof InviteEvent),
          take(1)
        )
        .subscribe({
          next: (inviteEvent) => {
            clearTimeout(timeoutHandle)
            resolve(inviteEvent)
          },
          error: reject,
        })
    })

    interactor.replayLastInvite()

    invitePromise
      .then((inviteEvent) => {
        debugLog('CANAL_BRIDGE', `Got InviteEvent, sending answer to thread ${threadId}`)
        interactor.sendEvent(inviteEvent.buildAnswer(message))
      })
      .catch(() => {
        debugLog('CANAL_BRIDGE', `No InviteEvent received, sending bare AnswerEvent to thread ${threadId}`)
        interactor.sendEvent(new AnswerEvent({ answer: message }))
      })
  }

  private ensureInstance(
    threadId: string,
    projectName: string,
    username: string,
    initialPrompt?: string
  ): CanalThreadHandleImpl {
    const existing = this.threadCodayManager.get(threadId)
    if (existing) {
      debugLog('CANAL_BRIDGE', `Reusing existing instance for thread ${threadId}`)
      return new CanalThreadHandleImpl(threadId, this.threadCodayManager)
    }

    debugLog('CANAL_BRIDGE', `Creating new Coday instance for thread ${threadId}`)
    const threadOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: false,
      project: projectName,
      thread: threadId,
      prompts: initialPrompt ? [initialPrompt] : [],
    }

    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, threadOptions)
    instance.prepareCoday()
    // Start the run loop so the instance processes messages
    instance.coday!.run().catch((error) => {
      debugLog('CANAL_BRIDGE', `Error in Coday run for thread ${threadId}:`, error)
    })

    return new CanalThreadHandleImpl(threadId, this.threadCodayManager)
  }
}
