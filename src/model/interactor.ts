import {AnswerEvent, ChoiceEvent, CodayEvent, ErrorEvent, InviteEvent, TextEvent, WarnEvent} from "./events"
import {filter, firstValueFrom, map, Observable, Subject, take} from "rxjs"

export abstract class Interactor {
  events = new Subject<CodayEvent>()
  
  async promptText(invite: string, defaultText?: string): Promise<string> {
    const inviteEvent = new InviteEvent({invite})
    const answer: Observable<string> = this.events.pipe(
      filter(e => e.parentKey === inviteEvent.timestamp),
      filter(e => e instanceof AnswerEvent),
      take(1),
      map(e => e.answer)
    )
    this.sendEvent(inviteEvent)
    return firstValueFrom(answer)
  }
  
  async chooseOption(
    options: string[],
    question: string,
    invite?: string,
  ): Promise<string> {
    const choiceEvent = new ChoiceEvent({options, question, invite})
    const answer: Observable<string> = this.events.pipe(
      filter(e => e.parentKey === choiceEvent.timestamp),
      filter(e => e instanceof AnswerEvent),
      take(1),
      map(e => e.answer)
    )
    this.sendEvent(choiceEvent)
    return firstValueFrom(answer)
  }
  
  displayText(text: string, speaker?: string): void {
    this.sendEvent(new TextEvent({text, speaker}))
  }
  
  warn(warning: string): void {
    this.sendEvent(new WarnEvent({warning}))
  }
  
  error(error: unknown): void {
    this.sendEvent(new ErrorEvent({error}))
  }
  
  sendEvent(event: CodayEvent): void {
    this.events.next(event)
  }
}
