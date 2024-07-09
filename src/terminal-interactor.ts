import {Interactor} from "./model/interactor"
import {input, select} from "@inquirer/prompts"
import chalk from "chalk"

export class TerminalInteractor implements Interactor {
  async promptText(invite: string, defaultText?: string): Promise<string> {
    this.addSeparator()
    const text = await input({
      message: `${chalk.black.bgWhite(invite)} : `,
      default: defaultText,
    })
    this.addSeparator()
    return text
  }
  
  async chooseOption(
    options: string[],
    question: string,
    invite?: string,
  ): Promise<string> {
    return select({
      message: `${invite} :\n${question}`,
      choices: options.map((option) => ({
        name: option,
        value: option,
      })),
    })
  }
  
  displayText(text: string, speaker?: string): void {
    const formattedText = speaker
      ? `\n${chalk.black.bgWhite(speaker)} : ${text}\n`
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
