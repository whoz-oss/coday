import {
  AnswerEvent,
  ChoiceEvent,
  CodayEvent,
  ErrorEvent,
  InviteEvent,
  TextEvent,
  ThinkingEvent,
  WarnEvent,
} from '../shared'
import { filter, firstValueFrom, map, Observable, Subject, Subscription, take, throttleTime } from 'rxjs'

export abstract class Interactor {
  events = new Subject<CodayEvent>()
  private thinking$ = new Subject<null>()
  private subs: Subscription[] = []
  private lastInviteEvent?: InviteEvent

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
    return firstValueFrom(answer)
  }

  async chooseOption(options: string[], question: string, invite?: string): Promise<string> {
    const choiceEvent = new ChoiceEvent({ options, invite: question, optionalQuestion: invite })
    const answer: Observable<string> = this.events.pipe(
      filter((e) => e.parentKey === choiceEvent.timestamp),
      filter((e) => e instanceof AnswerEvent),
      take(1),
      map((e) => e.answer)
    )
    this.sendEvent(choiceEvent)
    return firstValueFrom(answer)
  }

  displayText(text: string, speaker?: string): void {
    this.sendEvent(new TextEvent({ text, speaker }))
  }

  debug(log: any): void {
    if (!this.debugLevelEnabled) {
      return
    }
    const text = `DEBUG: ${log.toString()}`
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
  }
}
