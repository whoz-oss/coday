import {AnswerEvent} from "../shared"
import {Interactor} from "./interactor"

export class ServerInteractor extends Interactor {
  
  addAnswerEvent(answer: string, parentKey: string | undefined): void {
    const answerEvent = new AnswerEvent({answer, parentKey})
    this.sendEvent(answerEvent)
  }
  
}
