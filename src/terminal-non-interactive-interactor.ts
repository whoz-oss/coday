import {TerminalInteractor} from "./terminal-interactor"
import {ChoiceEvent, InviteEvent} from "./shared"

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
