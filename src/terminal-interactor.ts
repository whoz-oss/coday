import {Interactor} from "./interactor";
import * as readlineSync from 'readline-sync';

export class TerminalInteractor implements Interactor {
    promptText(invite: string, defaultText?: string): string {
        return readlineSync.question(invite, {defaultInput: defaultText})
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

    displayText(text: string): void {
        console.log(text)
    }

    warn(warning: string): void {
        console.warn(warning)
    }

    error(error: string): void {
        console.error(error)
    }

    addSeparator(): void {
        console.log("")
    }

}