import chalk from "chalk";
import {TerminalInteractor} from "./terminal-interactor";

export class TerminalNonInteractiveInteractor extends TerminalInteractor {

    private throwError(): string {
        throw new Error("Non-interactive session, this request will have no answer")
    }

    promptText(invite: string, defaultText?: string): string {
        return this.throwError()
    }

    chooseOption(options: string[], question: string, invite?: string): string {
        return this.throwError()
    }

    displayText(text: string, speaker?: string): void {
        const formattedText = speaker ? `${chalk.black.bgWhite(speaker)} : ${text}`: text
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