import {Context} from "../context";


export abstract class CommandHandler {
    abstract commandWord: string
    abstract description: string
    getSubCommand(command: string): string {
        return command.slice(this.commandWord.length).trim()
    }

    accept(command: string, context: Context): boolean {
        return !!command && command.toLowerCase().startsWith(this.commandWord)
    }

    abstract handle(command: string, context: Context): Promise<Context>
}