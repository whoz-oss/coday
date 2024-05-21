import {Interactor} from "./interactor";
import * as readlineSync from 'readline-sync';
import chalk from "chalk";

export class TerminalInteractor implements Interactor {
    promptText(invite: string, defaultText?: string): string {
        return readlineSync.question(chalk.black.bgWhite(invite), {defaultInput: defaultText})
    }

    chooseOption(options: string[], question: string, invite?: string): string {
        if (invite) {
            this.displayText(invite)
        }
        for (let i = 0; i < options.length; i++) {
            console.log(`${i} - ${options[i]}`)
        }
        return readlineSync.question(question)

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