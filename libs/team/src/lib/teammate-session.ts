import { Agent, AiThread, CodayEvent, Interactor, MessageEvent } from '@coday/model'
import { lastValueFrom } from 'rxjs'
import { filter, tap } from 'rxjs/operators'
import { Mailbox } from './mailbox'

export type TeammateStatus = 'idle' | 'working' | 'stopped'

export class TeammateSession {
  private _status: TeammateStatus = 'idle'
  private runPromise: Promise<void> | undefined
  private shouldStop: boolean = false

  get status(): TeammateStatus {
    return this._status
  }

  constructor(
    readonly name: string,
    readonly agent: Agent,
    readonly thread: AiThread,
    private readonly mailbox: Mailbox,
    private readonly interactor: Interactor,
    private readonly onStatusChange?: (name: string, status: TeammateStatus) => void
  ) {}

  /**
   * Start the teammate's run loop.
   * The teammate runs its initial task, then waits for messages.
   * Call shutdown() to stop the loop.
   */
  start(initialTask: string): void {
    if (this.runPromise) return // already running
    this.runPromise = this.runLoop(initialTask)
  }

  /**
   * The core run loop: execute task, go idle, wait for message, repeat.
   */
  private async runLoop(initialInput: string): Promise<void> {
    let currentInput = initialInput

    while (!this.shouldStop) {
      try {
        this.setStatus('working')

        const events = (await this.agent.run(currentInput, this.thread)).pipe(
          tap((e: CodayEvent) => this.interactor.sendEvent(e)),
          filter((e: CodayEvent) => e instanceof MessageEvent)
        )

        await lastValueFrom(events)

        // After completing work, check if we should continue
        if (this.shouldStop) break
        this.setStatus('idle')

        // Wait for next message (blocks until message arrives or shutdown)
        currentInput = await this.mailbox.waitForMessage(this.name)

        // Check for shutdown signal
        if (currentInput === '__SHUTDOWN__') {
          break
        }
      } catch (error: any) {
        this.interactor.error(`Teammate ${this.name} error: ${error.message}`)

        // On error, check if we should continue
        if (this.shouldStop) break
        this.setStatus('idle')

        try {
          currentInput = await this.mailbox.waitForMessage(this.name)
          if (currentInput === '__SHUTDOWN__') break
        } catch {
          break
        }
      }
    }

    this.setStatus('stopped')
  }

  /**
   * Request graceful shutdown. The teammate finishes current work then stops.
   */
  async shutdown(): Promise<void> {
    this.shouldStop = true
    this.mailbox.cancelWaiters(this.name)
    if (this.runPromise) {
      await this.runPromise
    }
  }

  private setStatus(status: TeammateStatus): void {
    this._status = status
    this.onStatusChange?.(this.name, status)
  }
}
