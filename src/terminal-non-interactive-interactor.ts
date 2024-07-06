import {Interactor} from "./model/interactor"
import chalk from "chalk"

export class TerminalNonInteractiveInteractor implements Interactor {
  private throwError(): string {
    throw new Error("Non-interactive session, this request will have no answer")
  }

  promptText(invite: string, defaultText?: string): Promise<string> {
    return Promise.resolve(this.throwError())
  }

  chooseOption(
    options: string[],
    question: string,
    invite?: string,
  ): Promise<string> {
    return Promise.resolve(this.throwError())
  }

  displayText(text: string, speaker?: string): void {
    const formattedText = speaker
      ? `${chalk.black.bgWhite(speaker)} : ${text}`
      : text
    console.log(formattedText)
  }

  warn(warning: string): void {
    console.warn(warning)
  }

  error(error: unknown): void {
    console.error(error)
  }

  addSeparator(): void {
    console.log("")
  }
}
