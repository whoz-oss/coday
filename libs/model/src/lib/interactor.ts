import {
  AnswerEvent,
  ChoiceEvent,
  CodayEvent,
  ErrorEvent,
  InviteEvent,
  TextEvent,
  ThinkingEvent,
  WarnEvent,
} from './coday-events'
import { filter, firstValueFrom, map, Observable, Subject, Subscription, take, throttleTime } from 'rxjs'

export abstract class Interactor {
  events = new Subject<CodayEvent>()
  private thinking$ = new Subject<null>()
  private subs: Subscription[] = []
  private lastInviteEvent?: InviteEvent
  private lastChoiceEvent?: ChoiceEvent

  debugLevelEnabled: boolean = false

  constructor() {
    this.subs.push(
      this.thinking$.pipe(throttleTime(ThinkingEvent.debounce)).subscribe(() => this.sendEvent(new ThinkingEvent({})))
    )
  }

  async promptText(invite: string, defaultText?: string): Promise<string> {
    const inviteEvent = new InviteEvent({ invite, defaultValue: defaultText })
    this.lastInviteEvent = inviteEvent
    const answer: Observable<string> = this.events.pipe(
      filter((e) => e.parentKey === inviteEvent.timestamp),
      filter((e) => e instanceof AnswerEvent),
      take(1),
      map((e) => e.answer)
    )
    this.sendEvent(inviteEvent)
    try {
      return await firstValueFrom(answer)
    } catch (error: any) {
      throw new Error(`No answer received over invite ${inviteEvent.timestamp} : ${error.message}`)
    }
  }

  /**
   * Prompts the user for a secret value (e.g. API key), masking the current value.
   * - If input matches the mask (default: '********'), returns currentValue.
   * - If input is blank/empty/undefined/null, returns undefined.
   * - Otherwise, returns input.
   */
  async promptSecretText(
    invite: string,
    currentValue?: string,
    mask: string = '********'
  ): Promise<string | undefined> {
    // Show mask only if there's a current value
    const defaultText = currentValue ? mask : ''
    const input = await this.promptText(invite, defaultText)

    if (input === mask && currentValue) {
      // User wants to keep the existing value
      return currentValue
    }
    if (!input) {
      // User wants to clear the field
      return undefined
    }
    // User provided a new value
    return input
  }

  async chooseOption(options: string[], question: string, invite?: string): Promise<string> {
    const choiceEvent = new ChoiceEvent({ options, invite: question, optionalQuestion: invite })
    this.lastChoiceEvent = choiceEvent
    const answer: Observable<string> = this.events.pipe(
      filter((e) => e.parentKey === choiceEvent.timestamp),
      filter((e) => e instanceof AnswerEvent),
      take(1),
      map((e) => e.answer)
    )
    this.sendEvent(choiceEvent)
    try {
      const result = await firstValueFrom(answer)
      // Clear once answered so replayLastChoice cannot re-post it
      this.lastChoiceEvent = undefined
      return result
    } catch (error: any) {
      throw new Error(`No answer received over choice ${choiceEvent.timestamp} : ${error.message}`)
    }
  }

  /**
   * Re-emit the last choice event if any.
   * Used by canal adapters to restore pending choice state after reconnection.
   * The re-emitted event is marked as replayed so canal adapters skip forwarding it outbound.
   */
  replayLastChoice(): void {
    if (this.lastChoiceEvent) {
      ;(this.lastChoiceEvent as any)._isReplayed = true
      this.sendEvent(this.lastChoiceEvent)
      ;(this.lastChoiceEvent as any)._isReplayed = false
    }
  }

  displayText(text: string, speaker?: string): void {
    this.sendEvent(new TextEvent({ text, speaker }))
  }

  debug(log: any): void {
    if (!this.debugLevelEnabled) {
      return
    }
    const text = `${new Date().toISOString()} DEBUG: ${log.toString()}`
    console.log(text)
    this.sendEvent(new TextEvent({ text }))
  }

  warn(warning: string): void {
    this.sendEvent(new WarnEvent({ warning }))
  }

  error(error: unknown): void {
    this.sendEvent(new ErrorEvent({ error }))
  }

  thinking(): void {
    this.thinking$.next(null)
  }

  sendEvent(event: CodayEvent): void {
    this.events.next(event)
  }

  /**
   * Re-emit the last invite event if any.
   * Used during reconnection to restore the input state.
   */
  replayLastInvite(): void {
    if (this.lastInviteEvent) {
      this.sendEvent(this.lastInviteEvent)
    }
  }

  kill() {
    this.subs.forEach((s) => s.unsubscribe())
    this.events.complete()
  }
}
