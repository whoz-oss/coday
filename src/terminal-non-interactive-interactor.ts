import {ChoiceEvent, InviteEvent} from "./model"
import {TerminalInteractor} from "./terminal-interactor"

export class TerminalNonInteractiveInteractor extends TerminalInteractor {
  
  override handleChoiceEvent(event: ChoiceEvent) {
    this.throwError()
  }
  
  override handleInviteEvent(event: InviteEvent) {
    this.throwError()
  }
  
  private throwError(): string {
    throw new Error("Non-interactive session, this request will have no answer")
  }
}
