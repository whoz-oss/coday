import { AnswerEvent } from '../shared'
import { Interactor } from './interactor'

export class ServerInteractor extends Interactor {
  constructor(private clientId: string) {
    super()
  }

  addAnswerEvent(answer: string, parentKey: string | undefined): void {
    const answerEvent = new AnswerEvent({ answer, parentKey })
    this.sendEvent(answerEvent)
  }

  kill() {
    console.log(`Interactor for clientId ${this.clientId} killed`)
    super.kill()
  }
}
