import {input, select} from "@inquirer/prompts"
import chalk from "chalk"
import {Interactor} from "./model"
import {ChoiceEvent, ErrorEvent, InviteEvent, TextEvent, WarnEvent} from "./shared"

export class TerminalInteractor extends Interactor {
  
  constructor() {
    super()
    this.events.subscribe((event) => {
      if (event instanceof TextEvent) {
        const {speaker, text} = event
        const formattedText = speaker
          ? `\n${chalk.black.bgWhite(speaker)} : ${text}\n`
          : text
        console.log(formattedText)
      }
      if (event instanceof WarnEvent) {
        console.warn(`${event.warning}\n`)
      }
      if (event instanceof ErrorEvent) {
        console.error(`${event.error}\n`)
      }
      if (event instanceof InviteEvent) {
        this.handleInviteEvent(event)
      }
      if (event instanceof ChoiceEvent) {
        this.handleChoiceEvent(event)
      }
    })
  }
  
  handleInviteEvent(event: InviteEvent): void {
    input({
      message: `\n${chalk.black.bgWhite(event.invite)} : `
    }).then((answer: string) => {
      this.sendEvent(event.buildAnswer(answer))
    })
  }
  
  handleChoiceEvent(event: ChoiceEvent): void {
    const {options, question, invite} = event
    select({
      message: invite ? `${invite} :\n${question}` : question,
      choices: options.map((option) => ({
        name: option,
        value: option,
      })),
    }).then((answer: string) => {
      this.sendEvent(event.buildAnswer(answer))
    })
  }
}
